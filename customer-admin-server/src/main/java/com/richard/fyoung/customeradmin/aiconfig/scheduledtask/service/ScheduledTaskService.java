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
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminScheduledTaskProperties;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    private static final int STATUS_ENABLED = 1;
    private static final int OUTPUT_MAX_LENGTH = 8000;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final AiScheduledTaskMapper taskMapper;
    private final AiScheduledTaskRunMapper runMapper;
    private final AiAgentMapper agentMapper;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final AdminScheduledTaskProperties properties;

    public ScheduledTaskService(AiScheduledTaskMapper taskMapper, AiScheduledTaskRunMapper runMapper,
                                 AiAgentMapper agentMapper, AdminAgentInstanceFactory agentInstanceFactory,
                                 AdminScheduledTaskProperties properties) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceFactory = agentInstanceFactory;
        this.properties = properties;
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
        AiScheduledTask task = new AiScheduledTask();
        fillFromRequest(task, request);
        taskMapper.insert(task);
    }

    public void update(Long id, ScheduledTaskSaveRequest request) {
        AiScheduledTask task = requireTask(id);
        requireTaskCodeAvailable(request.taskCode(), id);
        requireEnabledAgent(request.agentId());
        fillFromRequest(task, request);
        taskMapper.updateById(task);
    }

    public void delete(Long id) {
        requireTask(id);
        taskMapper.deleteById(id);
    }

    public void enable(Long id) {
        setEnabled(id, STATUS_ENABLED);
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
        if (agent.getStatus() == null || agent.getStatus() != STATUS_ENABLED) {
            throw new BizException(ResultCode.AGENT_DISABLED, "智能体未启用: " + agent.getAgentCode());
        }
    }

    private void fillFromRequest(AiScheduledTask task, ScheduledTaskSaveRequest request) {
        task.setTaskCode(request.taskCode());
        task.setTaskName(request.taskName());
        task.setAgentId(request.agentId());
        task.setPrompt(request.prompt());
        task.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : STATUS_ENABLED);
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
        vo.setEnabled(Integer.valueOf(STATUS_ENABLED).equals(task.getEnabled()));
        vo.setRemark(task.getRemark());
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
        if (task.getEnabled() == null || task.getEnabled() != STATUS_ENABLED) {
            throw new BizException(ResultCode.SCHEDULED_TASK_DISABLED, "定时任务未启用: " + taskCode);
        }
        return task;
    }
}
