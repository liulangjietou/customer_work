package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskPageQuery;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskRunVO;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskVO;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity.AiScheduledTask;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity.AiScheduledTaskRun;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskRunMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.scheduler.ScheduledTaskChangedEvent;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminScheduledTaskProperties;
import com.richard.fyoung.customeradmin.config.AdminSchedulerProperties;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务管理 + 执行（对应特性「基于 AgentScope 的定时任务」）。
 *
 * <p>{@link #execute(String, String)} 是唯一的执行入口，手动触发接口与 XXL-JOB JobHandler
 * 都复用它：查任务（不存在或 disabled fast-fail）→ 按 {@code agent_id} 现场组装 Agent
 * （{@link AdminAgentInstanceFactory#build(String)}，不复用启动期缓存实例——语义同
 * {@code McpService} 引用的动态智能体运行时工厂）→ 每次执行分配全新 sessionId，状态互不污染
 * → 同步调用并落执行历史（成功/失败都落，输出截断 8000 字符，不让模型输出把日志表撑爆）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ScheduledTaskService {


    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    public static final String TRIGGER_TYPE_MANUAL = "MANUAL";
    public static final String TRIGGER_TYPE_XXL_JOB = "XXL_JOB";
    public static final String TRIGGER_TYPE_INTERNAL = "INTERNAL";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    private static final int OUTPUT_MAX_LENGTH = 8000;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final AiScheduledTaskMapper taskMapper;
    private final AiScheduledTaskRunMapper runMapper;
    private final AiAgentMapper agentMapper;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final AdminScheduledTaskProperties properties;
    private final AdminSchedulerProperties schedulerProperties;
    private final ApplicationEventPublisher eventPublisher;

    public ScheduledTaskService(AiScheduledTaskMapper taskMapper, AiScheduledTaskRunMapper runMapper,
                                 AiAgentMapper agentMapper, AdminAgentInstanceFactory agentInstanceFactory,
                                 AdminScheduledTaskProperties properties, AdminSchedulerProperties schedulerProperties,
                                 ApplicationEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceFactory = agentInstanceFactory;
        this.properties = properties;
        this.schedulerProperties = schedulerProperties;
        this.eventPublisher = eventPublisher;
    }

    public IPage<ScheduledTaskVO> page(ScheduledTaskPageQuery query) {
        LambdaQueryWrapper<AiScheduledTask> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(AiScheduledTask::getTaskCode, query.getKeyword())
                .or().like(AiScheduledTask::getTaskName, query.getKeyword()));
        }
        wrapper.orderByDesc(AiScheduledTask::getCreateTime);

        IPage<AiScheduledTask> page = taskMapper.selectPage(new Page<>(query.getCurrent(), query.getSize()), wrapper);
        return page.convert(this::toVo);
    }

    public ScheduledTaskVO get(Long id) {
        return toVo(requireTask(id));
    }

    public void create(ScheduledTaskSaveRequest request) {
        requireTaskCodeAvailable(request.taskCode(), null);
        requireEnabledAgent(request.agentId());
        requireValidCron(request.cron());
        AiScheduledTask task = new AiScheduledTask();
        fillFromRequest(task, request);
        taskMapper.insert(task);
        publishChanged(task.getId(), false);
    }

    public void update(Long id, ScheduledTaskSaveRequest request) {
        AiScheduledTask task = requireTask(id);
        requireTaskCodeAvailable(request.taskCode(), id);
        requireEnabledAgent(request.agentId());
        requireValidCron(request.cron());
        fillFromRequest(task, request);
        taskMapper.updateById(task);
        publishChanged(id, false);
    }

    public void delete(Long id) {
        requireTask(id);
        taskMapper.deleteById(id);
        publishChanged(id, true);
    }

    public void enable(Long id) {
        setEnabled(id, StatusFlags.ENABLED);
    }

    public void disable(Long id) {
        setEnabled(id, 0);
    }

    private void setEnabled(Long id, int enabled) {
        requireTask(id);
        AiScheduledTask update = new AiScheduledTask();
        update.setId(id);
        update.setEnabled(enabled);
        taskMapper.updateById(update);
        publishChanged(id, false);
    }

    /** 发布任务变更事件，驱动内置调度器动态重注册/取消（xxl-job 模式下调度器自行忽略）。 */
    private void publishChanged(Long id, boolean removed) {
        eventPublisher.publishEvent(new ScheduledTaskChangedEvent(id, removed));
    }

    /** cron 校验：空表示不参与内置周期调度（合法）；非空则必须是合法 Spring cron，否则 fast-fail。 */
    private void requireValidCron(String cron) {
        if (!StringUtils.hasText(cron)) {
            return;
        }
        if (!CronExpression.isValidExpression(cron.trim())) {
            throw new BizException(ResultCode.SCHEDULER_CRON_INVALID, "cron 表达式非法: " + cron);
        }
    }

    public ScheduledTaskRunVO trigger(Long id) {
        AiScheduledTask task = requireTask(id);
        AiScheduledTaskRun run = execute(task.getTaskCode(), TRIGGER_TYPE_MANUAL);
        return toRunVo(run);
    }

    public IPage<ScheduledTaskRunVO> runs(Long id, ScheduledTaskPageQuery query) {
        requireTask(id);
        LambdaQueryWrapper<AiScheduledTaskRun> wrapper = new LambdaQueryWrapper<AiScheduledTaskRun>()
            .eq(AiScheduledTaskRun::getTaskId, id)
            .orderByDesc(AiScheduledTaskRun::getStartTime);
        IPage<AiScheduledTaskRun> page = runMapper.selectPage(new Page<>(query.getCurrent(), query.getSize()), wrapper);
        return page.convert(this::toRunVo);
    }

    /**
     * 定时任务唯一执行入口：手动触发接口与 XXL-JOB JobHandler 都复用本方法。
     *
     * <p>disabled 任务 fast-fail 拒绝（不落执行历史——从未真正尝试执行，跟"执行失败"语义不同）；
     * 一旦进入执行阶段，无论成功失败都落一条历史记录，失败也要留证方便运营排查。</p>
     */
    /**
     * 调度侧的执行入口：先跨租户按 {@code task_code} 定位任务，再切到它所属的租户上下文执行。
     *
     * <p>调度线程（内置调度器 / XXL-JOB 执行器）不是 Web 请求，没有登录态可推导租户，
     * 而 {@link #execute} 全程要读写带 {@code tenant_id} 的表，缺上下文会 fail-closed 直接抛错。
     * 定位这一步是明确的跨租户运维扫描，因此走 {@code CrossTenantOperations} 白名单；
     * {@code task_code} 全局唯一（uk_ai_scheduled_task_code），定位结果不会有歧义。</p>
     *
     * <p>手动触发不走这里——那是 Web 请求，上下文已经由拦截器设好，直接调 {@link #execute}
     * 才能保证"只能触发自己租户的任务"。</p>
     */
    public AiScheduledTaskRun executeFromScheduler(String taskCode, String triggerType) {
        String tenantId = CrossTenantOperations.execute(() -> {
            AiScheduledTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<AiScheduledTask>().eq(AiScheduledTask::getTaskCode, taskCode));
            return task == null ? null : task.getTenantId();
        });
        if (tenantId == null || tenantId.isBlank()) {
            return TenantContext.callWith(TenantContext.DEFAULT, () -> execute(taskCode, triggerType));
        }
        return TenantContext.callWith(tenantId, () -> execute(taskCode, triggerType));
    }

    public AiScheduledTaskRun execute(String taskCode, String triggerType) {
        AiScheduledTask task = requireEnabledTaskByCode(taskCode);
        LocalDateTime startTime = LocalDateTime.now();
        AiScheduledTaskRun run = new AiScheduledTaskRun();
        run.setTaskId(task.getId());
        run.setTaskCode(taskCode);
        run.setTriggerType(triggerType);
        run.setStartTime(startTime);
        try {
            String output = callAgent(task);
            run.setStatus(STATUS_SUCCESS);
            run.setOutput(truncate(output, OUTPUT_MAX_LENGTH));
        } catch (Exception e) {
            log.error("scheduled task execute failed, code={}, taskCode={}", "SCHED-TASK-EXEC-FAIL", taskCode, e);
            run.setStatus(STATUS_FAILED);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            run.setErrorMessage(truncate(message, ERROR_MESSAGE_MAX_LENGTH));
        } finally {
            LocalDateTime endTime = LocalDateTime.now();
            run.setEndTime(endTime);
            run.setCostMs(Duration.between(startTime, endTime).toMillis());
            runMapper.insert(run);
        }
        return run;
    }

    /** 按 agent_id 现场组装 Agent 并同步调用一次；每次执行分配全新 sessionId，状态天然隔离。 */
    private String callAgent(AiScheduledTask task) {
        AiAgent agent = agentMapper.selectById(task.getAgentId());
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "定时任务关联的智能体不存在: " + task.getAgentId());
        }
        Agent runtimeAgent = agentInstanceFactory.build(agent.getAgentCode());
        String sessionId = "sched-" + task.getTaskCode() + "-" + System.currentTimeMillis();
        RuntimeContext ctx = agentInstanceFactory.contextFor(agent.getAgentCode(), sessionId);
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text(task.getPrompt()).build())
            .build();
        Msg reply = callWithContext(runtimeAgent, List.of(userMsg), ctx)
            .block(Duration.ofSeconds(properties.getExecuteTimeoutSeconds()));
        return reply == null ? null : reply.getTextContent();
    }

    /**
     * {@code call(List, RuntimeContext)} 只直接声明在 {@link ReActAgent}/{@link HarnessAgent} 各自的类上
     * （未收敛进共享的 {@link Agent} 接口），故按运行时具体类型分派——同 {@code ChatService#streamEvents}
     * 的手法，{@link AdminAgentInstanceFactory#build} 只会产出这两种之一。
     */
    private Mono<Msg> callWithContext(Agent agent, List<Msg> msgs, RuntimeContext ctx) {
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent.call(msgs, ctx);
        }
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.call(msgs, ctx);
        }
        throw new IllegalStateException("unsupported agent runtime type: " + agent.getClass());
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private void requireTaskCodeAvailable(String taskCode, Long excludeId) {
        LambdaQueryWrapper<AiScheduledTask> wrapper = new LambdaQueryWrapper<AiScheduledTask>()
            .eq(AiScheduledTask::getTaskCode, taskCode);
        if (excludeId != null) {
            wrapper.ne(AiScheduledTask::getId, excludeId);
        }
        if (taskMapper.exists(wrapper)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "taskCode 已存在: " + taskCode);
        }
    }

    private void requireEnabledAgent(Long agentId) {
        AiAgent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + agentId);
        }
        if (agent.getStatus() == null || agent.getStatus() != StatusFlags.ENABLED) {
            throw new BizException(ResultCode.AGENT_DISABLED, "智能体未启用: " + agent.getAgentCode());
        }
    }

    private void fillFromRequest(AiScheduledTask task, ScheduledTaskSaveRequest request) {
        task.setTaskCode(request.taskCode());
        task.setTaskName(request.taskName());
        task.setAgentId(request.agentId());
        task.setPrompt(request.prompt());
        task.setCron(StringUtils.hasText(request.cron()) ? request.cron().trim() : null);
        task.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : StatusFlags.ENABLED);
        task.setRemark(request.remark());
    }

    private ScheduledTaskVO toVo(AiScheduledTask task) {
        ScheduledTaskVO vo = new ScheduledTaskVO();
        vo.setId(task.getId());
        vo.setTaskCode(task.getTaskCode());
        vo.setTaskName(task.getTaskName());
        vo.setAgentId(task.getAgentId());
        AiAgent agent = agentMapper.selectById(task.getAgentId());
        vo.setAgentName(agent == null ? null : agent.getAgentName());
        vo.setPrompt(task.getPrompt());
        vo.setCron(task.getCron());
        vo.setEnabled(Integer.valueOf(StatusFlags.ENABLED).equals(task.getEnabled()));
        vo.setRemark(task.getRemark());
        vo.setScheduleMode(schedulerProperties.getMode());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        return vo;
    }

    private ScheduledTaskRunVO toRunVo(AiScheduledTaskRun run) {
        ScheduledTaskRunVO vo = new ScheduledTaskRunVO();
        vo.setId(run.getId());
        vo.setTaskId(run.getTaskId());
        vo.setTaskCode(run.getTaskCode());
        vo.setTriggerType(run.getTriggerType());
        vo.setStartTime(run.getStartTime());
        vo.setEndTime(run.getEndTime());
        vo.setCostMs(run.getCostMs());
        vo.setStatus(run.getStatus());
        vo.setOutput(run.getOutput());
        vo.setErrorMessage(run.getErrorMessage());
        return vo;
    }

    private AiScheduledTask requireTask(Long id) {
        AiScheduledTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "定时任务不存在: " + id);
        }
        return task;
    }

    private AiScheduledTask requireEnabledTaskByCode(String taskCode) {
        AiScheduledTask task = taskMapper.selectOne(
            new LambdaQueryWrapper<AiScheduledTask>().eq(AiScheduledTask::getTaskCode, taskCode));
        if (task == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "定时任务不存在: " + taskCode);
        }
        if (task.getEnabled() == null || task.getEnabled() != StatusFlags.ENABLED) {
            throw new BizException(ResultCode.SCHEDULED_TASK_DISABLED, "定时任务未启用: " + taskCode);
        }
        return task;
    }
}
