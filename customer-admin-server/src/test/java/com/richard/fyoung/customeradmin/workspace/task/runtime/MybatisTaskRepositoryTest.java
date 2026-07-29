package com.richard.fyoung.customeradmin.workspace.task.runtime;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.workspace.task.entity.AiAgentTask;
import com.richard.fyoung.customeradmin.workspace.task.mapper.AiAgentTaskMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台任务落库仓储单测：状态流转、取消的两个时间窗、进程重启后按库记录重建、远程 spec 拒绝。
 * @author owlzhangfq@gmail.com
 */
class MybatisTaskRepositoryTest {

    private static final String TASK_ID = "task-1";
    private static final String SESSION_ID = "session-1";
    private static final String SUB_AGENT = "code-reviewer";
    private static final long WAIT_MS = 3000;

    private AiAgentTaskMapper mapper;
    private MybatisTaskRepository repository;

    /**
     * MyBatis-Plus 的 Lambda 条件构造依赖实体的 TableInfo 缓存，容器外单测里没人初始化它，
     * 不预热会在第一次 {@code .set(AiAgentTask::getXxx, ...)} 时抛 "can not find lambda cache"。
     * 写法与 {@code AiCodingAuditServiceTest} 一致。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentTask.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiAgentTaskMapper.class);
        when(mapper.insert(any(AiAgentTask.class))).thenReturn(1);
        when(mapper.update(any(), any())).thenReturn(1);
        stubExistingRecord(TaskStatus.PENDING, false);

        repository = new MybatisTaskRepository(mapper, new AgentTaskExecutorProperties());
        // 不走 @PostConstruct（无容器），显式给一个单线程池，配合 awaitIdle 让异步执行可断言
        repository.useExecutor(Executors.newSingleThreadExecutor());
    }

    /** 让 selectByTaskId 返回一条指定状态的记录。 */
    private void stubExistingRecord(TaskStatus status, boolean cancelRequested) {
        AiAgentTask record = new AiAgentTask();
        record.setId(1L);
        record.setTaskId(TASK_ID);
        record.setSubAgentId(SUB_AGENT);
        record.setParentSessionId(SESSION_ID);
        record.setStatus(status.name());
        record.setCancelRequested(cancelRequested);
        record.setCreatedAt(LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(record);
    }

    /** 收集所有 update 调用里被 set 的值（LambdaUpdateWrapper 把值放在 paramNameValuePairs 里）。 */
    @SuppressWarnings("unchecked")
    private List<Object> capturedSetValues() {
        ArgumentCaptor<LambdaUpdateWrapper<AiAgentTask>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, atLeastOnce()).update(any(), captor.capture());
        List<Object> values = new ArrayList<>();
        captor.getAllValues().forEach(w -> values.addAll(w.getParamNameValuePairs().values()));
        return values;
    }

    private TaskRunSpec.LocalTaskRunSpec localSpec(java.util.function.Supplier<String> supplier) {
        return new TaskRunSpec.LocalTaskRunSpec(supplier);
    }

    @Test
    void putTask_shouldInsertPendingRecordAndRunToCompletion() throws Exception {
        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            localSpec(() -> "审查完成，无高危问题"));

        assertTrue(task.waitForCompletion(WAIT_MS), "任务应在超时前跑完");
        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
        assertEquals("审查完成，无高危问题", task.getResult());

        ArgumentCaptor<AiAgentTask> inserted = ArgumentCaptor.forClass(AiAgentTask.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(TaskStatus.PENDING.name(), inserted.getValue().getStatus(), "创建即落 PENDING");
        assertEquals(SESSION_ID, inserted.getValue().getParentSessionId());

        List<Object> setValues = capturedSetValues();
        assertTrue(setValues.contains(TaskStatus.RUNNING.name()), "应流转到 RUNNING");
        assertTrue(setValues.contains(TaskStatus.COMPLETED.name()), "应流转到 COMPLETED");
        assertTrue(setValues.contains("审查完成，无高危问题"), "结果应落库");
    }

    @Test
    void failedTask_shouldPersistFailedStatusAndKeepError() throws Exception {
        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            localSpec(() -> {
                throw new IllegalStateException("模型调用超时");
            }));

        assertTrue(task.waitForCompletion(WAIT_MS));
        assertEquals(TaskStatus.FAILED, task.getTaskStatus());
        assertNotNull(task.getError(), "失败原因应能被父智能体取到");

        List<Object> setValues = capturedSetValues();
        assertTrue(setValues.contains(TaskStatus.FAILED.name()), "应落 FAILED");
        assertTrue(setValues.contains("模型调用超时"), "错误信息应落库");
    }

    @Test
    void cancelBeforeExecution_shouldSkipRunningTheSupplier() throws Exception {
        // 任务还在队列里排队时被取消：future 中断无效，只能靠开跑前查一次取消标志自我了断
        stubExistingRecord(TaskStatus.PENDING, true);
        AtomicBoolean executed = new AtomicBoolean(false);

        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            localSpec(() -> {
                executed.set(true);
                return "不该跑到这里";
            }));

        assertTrue(task.waitForCompletion(WAIT_MS));
        assertTrue(repository.awaitIdle(WAIT_MS));
        assertFalse(executed.get(), "已请求取消的任务不该真的执行");
        assertNull(task.getResult(), "取消的任务没有结果");
        assertTrue(capturedSetValues().contains(TaskStatus.CANCELLED.name()));
    }

    @Test
    void cancelTask_shouldPersistFlagAndInterruptRunningFuture() {
        stubExistingRecord(TaskStatus.RUNNING, false);
        CompletableFuture<String> running = new CompletableFuture<>();
        repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            new TaskRunSpec.AdoptedTaskRunSpec(running));

        assertTrue(repository.cancelTask(RuntimeContext.empty(), SESSION_ID, TASK_ID));

        assertTrue(running.isCancelled(), "本进程内正在跑的任务应被中断");
        List<Object> setValues = capturedSetValues();
        assertTrue(setValues.contains(Boolean.TRUE), "取消标志应落库（供排队中/跨实例的任务读取）");
        assertTrue(setValues.contains(TaskStatus.CANCELLED.name()), "非终态任务应置为 CANCELLED");
    }

    @Test
    void cancelTask_shouldReturnFalse_whenTaskUnknown() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertFalse(repository.cancelTask(RuntimeContext.empty(), SESSION_ID, "not-exist"));
    }

    @Test
    void getTask_shouldRebuildFromDatabase_whenFutureLostAfterRestart() {
        // 进程重启后内存里没有 future 了，但库里有终态记录——父智能体仍应拿得到结果
        AiAgentTask record = new AiAgentTask();
        record.setTaskId(TASK_ID);
        record.setSubAgentId(SUB_AGENT);
        record.setStatus(TaskStatus.COMPLETED.name());
        record.setResult("上个进程跑完的结果");
        when(mapper.selectOne(any())).thenReturn(record);

        BackgroundTask task = repository.getTask(RuntimeContext.empty(), SESSION_ID, TASK_ID);

        assertNotNull(task);
        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
        assertEquals("上个进程跑完的结果", task.getResult());
    }

    @Test
    void getTask_shouldRebuildOrphanAsFailed_whenRecordStuckInRunning() {
        // 库里停在 RUNNING 但内存没有 future = 上个进程的孤儿，不能让父智能体一直等下去
        AiAgentTask record = new AiAgentTask();
        record.setTaskId(TASK_ID);
        record.setStatus(TaskStatus.RUNNING.name());
        when(mapper.selectOne(any())).thenReturn(record);

        BackgroundTask task = repository.getTask(RuntimeContext.empty(), SESSION_ID, TASK_ID);

        assertEquals(TaskStatus.FAILED, task.getTaskStatus());
    }

    @Test
    void getTask_shouldReturnNull_whenNeitherMemoryNorDatabaseHasIt() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(repository.getTask(RuntimeContext.empty(), SESSION_ID, "not-exist"));
    }

    @Test
    void remoteSpec_shouldFailFastWithExplicitError() {
        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            new TaskRunSpec.RemoteTaskRunSpec("http://remote", Map.of(), SUB_AGENT, "input"));

        assertEquals(TaskStatus.FAILED, task.getTaskStatus(), "远程任务在后台管理端无执行场景，应明确失败");
        assertTrue(capturedSetValues().contains(TaskStatus.FAILED.name()));
    }

    @Test
    void removeTask_shouldOnlyDropMemoryReference() {
        repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID, localSpec(() -> "x"));
        assertEquals(1, repository.activeTaskCount());

        repository.removeTask(RuntimeContext.empty(), SESSION_ID, TASK_ID);

        assertEquals(0, repository.activeTaskCount());
        // 库记录是管理台要看的历史，不能跟着删
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
    }
}
