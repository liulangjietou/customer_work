package com.richard.fyoung.customeradmin.workspace.task.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customeradmin.workspace.task.entity.AiAgentTask;
import com.richard.fyoung.customeradmin.workspace.task.mapper.AiAgentTaskMapper;
import com.richard.fyoung.customeradmin.workspace.runtime.WorkspaceRuntimeScope;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台委派任务仓储的落库实现（{@code agent_spawn} 传 {@code timeout_seconds=0} 时走这条）。
 *
 * <h3>为什么替换框架自带的 WorkspaceTaskRepository</h3>
 * <p>框架实现把任务状态写在 agent 工作区的文件里，那份状态只服务一件事：父智能体在自己的 ReAct
 * 循环里回头查"我派出去的任务好了没"。管理台要的是另一回事——跨会话、跨智能体列出所有任务，
 * 带分页、权限与审计。扫工作区文件树做这些既慢又脆，任务还会随工作区清理一起消失。
 * {@code HarnessAgent.Builder} 恰好暴露了 {@code taskRepository(...)} 注入点，于是这里整体接管，
 * 让后台任务成为管理台的一等公民。</p>
 *
 * <p>没有沿用"装饰器包一层框架实现"的写法：那样状态会同时存在文件与库两份，而状态变化发生在
 * 框架实现内部的 future 回调里，装饰器根本拦不到，双写必然不一致。本类直接实现接口，
 * 状态只有库这一个真源。</p>
 *
 * <h3>执行语义（与框架 WorkspaceTaskRepository 对齐）</h3>
 * <ul>
 *   <li>创建即落 {@code PENDING}，进线程池后转 {@code RUNNING}，结束转 {@code COMPLETED}/{@code FAILED}；</li>
 *   <li>执行<b>前后各查一次</b>取消标志：排队期间被取消的任务不该再跑，跑完才被取消的不该覆盖成功态；</li>
 *   <li>取消 = 内存 future 中断 + 落 {@code cancel_requested}，两者都做——future 只能中断本进程内
 *       正在跑的任务，标志位是给"任务还在排队"和"跨实例"两种情况兜底的。</li>
 * </ul>
 *
 * <h3>远程任务不支持</h3>
 * <p>{@code RemoteTaskRunSpec}（子智能体跑在别的进程，走 agent 协议 HTTP）在后台管理端没有接入场景——
 * {@code AdminAgentInstanceFactory} 注册的子智能体全部是本进程内按数据库配置构建的。收到这类 spec
 * 时落一条失败记录并返回失败的 future，让模型拿到明确错误，而不是静默不执行。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class MybatisTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisTaskRepository.class);

    private static final String CODE_TASK_PERSIST_FAIL = "AGENT-TASK-PERSIST-FAIL";
    private static final String CODE_TASK_REMOTE_UNSUPPORTED = "AGENT-TASK-REMOTE-UNSUPPORTED";
    private static final String CODE_TASK_RESTART_ORPHAN = "AGENT-TASK-RESTART-ORPHAN";

    /** 结果/错误文本落库上限：列是 MEDIUMTEXT，截断只为挡住异常巨大的输出撑爆单行。 */
    private static final int MAX_TEXT_LENGTH = 100_000;
    /** 进程重启后遗留的非终态任务统一置为该错误信息。 */
    private static final String RESTART_ERROR = "task interrupted by service restart";
    private static final String REMOTE_ERROR = "remote background task is not supported by admin server";

    private final AiAgentTaskMapper taskMapper;
    private final AgentTaskExecutorProperties properties;

    /**
     * 本进程内活跃任务：{@code tenantAgent::sessionId::taskId -> BackgroundTask}。
     *
     * <p>库里存的是状态快照，而框架要的 {@link BackgroundTask} 带着可等待、可中断的
     * {@link CompletableFuture}——那个 future 只存在于创建它的这个进程里，没法持久化，
     * 因此内存表与库表两者都需要，各管一段。</p>
     */
    private final ConcurrentHashMap<String, BackgroundTask> activeTasks = new ConcurrentHashMap<>();

    private ExecutorService executor;

    public MybatisTaskRepository(AiAgentTaskMapper taskMapper, AgentTaskExecutorProperties properties) {
        this.taskMapper = taskMapper;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.executor = Executors.newFixedThreadPool(properties.getPoolSize(), namedThreadFactory());
        if (properties.isCleanupOrphansOnStartup()) {
            cleanupOrphanTasks();
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        log.info("[agent-task] executor shutdown requested");
    }

    /**
     * 把进程重启前遗留的非终态任务标记为失败。
     *
     * <p>那些任务的执行线程已随上个进程消失，不可能再推进，留在库里就是永远转圈的假 RUNNING，
     * 对使用者是纯误导。<b>注意</b>：这是按"后台管理端单实例部署"的前提做的全局清理——若将来
     * 多实例部署，本实例启动会误伤其它实例正在跑的任务，届时需要改成按实例 ID 划分清理范围。</p>
     */
    private void cleanupOrphanTasks() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LambdaUpdateWrapper<AiAgentTask> update = new LambdaUpdateWrapper<AiAgentTask>()
                .in(AiAgentTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name())
                .set(AiAgentTask::getStatus, TaskStatus.FAILED.name())
                .set(AiAgentTask::getErrorMessage, RESTART_ERROR)
                .set(AiAgentTask::getFinishedAt, now)
                .set(AiAgentTask::getUpdatedAt, now);
            int affected = taskMapper.update(null, update);
            if (affected > 0) {
                log.info("[agent-task] marked {} orphan tasks as failed after restart", affected);
            }
        } catch (Exception e) {
            // 清理失败不能阻断应用启动：任务功能本身仍可用，只是列表里会残留假 RUNNING
            log.error("[agent-task] orphan cleanup failed, code={}", CODE_TASK_RESTART_ORPHAN, e);
        }
    }

    @Override
    public BackgroundTask putTask(RuntimeContext rc, String taskId, String subAgentId,
                                  String sessionId, TaskRunSpec spec) {
        insertRecord(taskId, subAgentId, sessionId, agentCodeOf(rc));
        String tenantId = TenantContext.get();

        CompletableFuture<String> future;
        if (spec instanceof TaskRunSpec.LocalTaskRunSpec local) {
            future = CompletableFuture.supplyAsync(
                () -> TenantContext.callWith(tenantId, () -> runLocal(sessionId, taskId, local.execution())), executor);
        } else if (spec instanceof TaskRunSpec.AdoptedTaskRunSpec adopted) {
            // 同步调用超时后被提升为后台任务：future 已经在跑，只补挂状态回写，绝不能重复提交执行
            future = adopted.future();
            updateStatus(taskId, TaskStatus.RUNNING, null, null);
            future.whenComplete((result, err) -> {
                TenantContext.runWith(tenantId, () -> {
                    if (err == null) {
                        updateStatus(taskId, TaskStatus.COMPLETED, result, null);
                    } else {
                        updateStatus(taskId, TaskStatus.FAILED, null, rootMessage(err));
                    }
                });
            });
        } else {
            log.error("[agent-task] remote task spec is not supported, code={}, taskId={}, subAgentId={}",
                CODE_TASK_REMOTE_UNSUPPORTED, taskId, subAgentId);
            updateStatus(taskId, TaskStatus.FAILED, null, REMOTE_ERROR);
            future = CompletableFuture.failedFuture(new UnsupportedOperationException(REMOTE_ERROR));
        }

        BackgroundTask task = new BackgroundTask(taskId, subAgentId, future);
        activeTasks.put(key(rc, sessionId, taskId), task);
        log.info("[agent-task] task submitted: taskId={} subAgentId={} sessionId={}", taskId, subAgentId, sessionId);
        return task;
    }

    /**
     * 本地任务执行体：跑在线程池里，负责整条状态流转。
     *
     * <p>执行前后各查一次取消标志，对应两个真实存在的时间窗：任务在队列里排队时被取消（此时
     * future 还没开始跑，中断无效），以及任务跑完了但结果还没落库时被取消（此时不该把成功态
     * 盖掉用户的取消意图）。</p>
     */
    private String runLocal(String sessionId, String taskId, java.util.function.Supplier<String> execution) {
        if (isCancelRequested(taskId)) {
            updateStatus(taskId, TaskStatus.CANCELLED, null, null);
            return null;
        }
        updateStatus(taskId, TaskStatus.RUNNING, null, null);
        try {
            String result = execution.get();
            if (isCancelRequested(taskId)) {
                updateStatus(taskId, TaskStatus.CANCELLED, null, null);
                return null;
            }
            updateStatus(taskId, TaskStatus.COMPLETED, result, null);
            log.info("[agent-task] task completed: taskId={} sessionId={}", taskId, sessionId);
            return result;
        } catch (Exception e) {
            updateStatus(taskId, TaskStatus.FAILED, null, rootMessage(e));
            log.error("[agent-task] task failed, code={}, taskId={}, sessionId={}",
                CODE_TASK_PERSIST_FAIL, taskId, sessionId, e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    @Override
    public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
        BackgroundTask active = activeTasks.get(key(rc, sessionId, taskId));
        if (active != null) {
            active.updateLastCheckedAt();
            return active;
        }
        // 进程重启后 future 已丢失，用库里的终态快照重建一个"已完成"的 BackgroundTask，
        // 让父智能体仍能拿到结果，而不是看到"任务不存在"
        AiAgentTask record = selectByTaskId(taskId);
        return record == null ? null : toBackgroundTask(record);
    }

    @Override
    public Collection<BackgroundTask> listTasks(RuntimeContext rc, String sessionId, TaskStatus filter) {
        LambdaQueryWrapper<AiAgentTask> query = new LambdaQueryWrapper<AiAgentTask>()
            .eq(AiAgentTask::getParentSessionId, sessionId)
            .orderByDesc(AiAgentTask::getId);
        if (filter != null) {
            query.eq(AiAgentTask::getStatus, filter.name());
        }
        List<AiAgentTask> records = taskMapper.selectList(query);
        if (CollectionUtils.isEmpty(records)) {
            return List.of();
        }
        List<BackgroundTask> tasks = new ArrayList<>(records.size());
        for (AiAgentTask record : records) {
            // 活跃任务优先返回内存实例：它带着真正可等待的 future，重建出来的只是终态快照
            BackgroundTask active = activeTasks.get(key(rc, sessionId, record.getTaskId()));
            tasks.add(active != null ? active : toBackgroundTask(record));
        }
        return tasks;
    }

    @Override
    public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
        AiAgentTask record = selectByTaskId(taskId);
        if (record == null) {
            return false;
        }
        // 标志位先落库：正在排队的任务靠它在开跑前自我了断，本进程外的任务也只能靠它
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AiAgentTask> update = new LambdaUpdateWrapper<AiAgentTask>()
            .eq(AiAgentTask::getTaskId, taskId)
            .set(AiAgentTask::getCancelRequested, true)
            .set(AiAgentTask::getUpdatedAt, now);
        if (!isTerminal(record.getStatus())) {
            update.set(AiAgentTask::getStatus, TaskStatus.CANCELLED.name())
                .set(AiAgentTask::getFinishedAt, now);
        }
        taskMapper.update(null, update);

        BackgroundTask active = activeTasks.get(key(rc, sessionId, taskId));
        if (active != null) {
            active.cancel(true);
        }
        log.info("[agent-task] task cancel requested: taskId={} sessionId={} wasTerminal={}",
            taskId, sessionId, isTerminal(record.getStatus()));
        return true;
    }

    @Override
    public void removeTask(RuntimeContext rc, String sessionId, String taskId) {
        // 只摘内存引用，不删库：库里那条是管理台要看的历史，删了就查不到"这个任务当时跑成什么样"
        activeTasks.remove(key(rc, sessionId, taskId));
    }

    @Override
    public void clear() {
        activeTasks.clear();
    }

    /** 把库记录还原成框架要的 {@link BackgroundTask}（future 按终态构造，非终态一律当中断处理）。 */
    private BackgroundTask toBackgroundTask(AiAgentTask record) {
        TaskStatus status = parseStatus(record.getStatus());
        CompletableFuture<String> future = new CompletableFuture<>();
        switch (status) {
            case COMPLETED -> future.complete(record.getResult());
            case FAILED -> future.completeExceptionally(new IllegalStateException(
                StringUtils.hasText(record.getErrorMessage()) ? record.getErrorMessage() : "task failed"));
            case CANCELLED -> future.cancel(true);
            // PENDING/RUNNING 却不在内存表里 = 上个进程留下的孤儿，等下去永远不会有结果
            default -> future.completeExceptionally(new IllegalStateException(RESTART_ERROR));
        }
        return new BackgroundTask(record.getTaskId(), record.getSubAgentId(), future);
    }

    private void insertRecord(String taskId, String subAgentId, String sessionId, String agentCode) {
        LocalDateTime now = LocalDateTime.now();
        AiAgentTask record = new AiAgentTask();
        record.setTaskId(taskId);
        record.setSubAgentId(subAgentId);
        record.setParentSessionId(sessionId);
        record.setParentAgentCode(agentCode);
        record.setStatus(TaskStatus.PENDING.name());
        record.setCancelRequested(false);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        taskMapper.insert(record);
    }

    /**
     * 状态回写。异常一律吞掉只记日志：这些调用发生在任务执行线程里，
     * 落库失败不该把已经跑完的任务结果连带弄丢，更不该反向影响智能体主链路。
     */
    private void updateStatus(String taskId, TaskStatus status, String result, String errorMessage) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LambdaUpdateWrapper<AiAgentTask> update = new LambdaUpdateWrapper<AiAgentTask>()
                .eq(AiAgentTask::getTaskId, taskId)
                .set(AiAgentTask::getStatus, status.name())
                .set(AiAgentTask::getUpdatedAt, now);
            if (status == TaskStatus.RUNNING) {
                update.set(AiAgentTask::getStartedAt, now);
            }
            if (status.isTerminal()) {
                update.set(AiAgentTask::getFinishedAt, now);
            }
            if (result != null) {
                update.set(AiAgentTask::getResult, truncate(result));
            }
            if (errorMessage != null) {
                update.set(AiAgentTask::getErrorMessage, truncate(errorMessage));
            }
            taskMapper.update(null, update);
        } catch (Exception e) {
            log.error("[agent-task] status persist failed, code={}, taskId={}, status={}",
                CODE_TASK_PERSIST_FAIL, taskId, status, e);
        }
    }

    private boolean isCancelRequested(String taskId) {
        AiAgentTask record = selectByTaskId(taskId);
        return record != null && Boolean.TRUE.equals(record.getCancelRequested());
    }

    private AiAgentTask selectByTaskId(String taskId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<AiAgentTask>()
            .eq(AiAgentTask::getTaskId, taskId).last("LIMIT 1"));
    }

    private boolean isTerminal(String status) {
        return parseStatus(status).isTerminal();
    }

    /** 库里的状态字符串转枚举；脏数据按 PENDING 处理（不抛异常打断链路）。 */
    private TaskStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return TaskStatus.PENDING;
        }
        try {
            return TaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return TaskStatus.PENDING;
        }
    }

    private String truncate(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    /** {@code AdminAgentInstanceFactory#contextFor} 把租户作用域 Agent 编码放在 userId 上，这里还原业务编码。 */
    private String agentCodeOf(RuntimeContext rc) {
        return rc == null ? null : WorkspaceRuntimeScope.rawAgent(rc.getUserId());
    }

    private String rootMessage(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
            ? error.getCause() : error;
        return StringUtils.hasText(cause.getMessage()) ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private String key(RuntimeContext rc, String sessionId, String taskId) {
        String scopedAgent = rc == null || !StringUtils.hasText(rc.getUserId()) ? "anonymous" : rc.getUserId();
        return scopedAgent + "::" + sessionId + "::" + taskId;
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "agent-task-" + seq.incrementAndGet());
            // 守护线程：任务线程不该拖住 JVM 退出（PreDestroy 已经 shutdownNow 过一次）
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 供单测断言内存活跃表规模。 */
    int activeTaskCount() {
        return activeTasks.size();
    }

    /** 供单测直接注入线程池（避免依赖 {@code @PostConstruct} 的容器时机）。 */
    void useExecutor(ExecutorService executorService) {
        this.executor = executorService;
    }

    /** 供单测等待线程池排空。 */
    boolean awaitIdle(long timeoutMs) throws InterruptedException {
        executor.shutdown();
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
