package com.richard.fyoung.customeradmin.workspace.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.task.dto.AgentTaskPageQuery;
import com.richard.fyoung.customeradmin.workspace.task.dto.AgentTaskVO;
import com.richard.fyoung.customeradmin.workspace.task.entity.AiAgentTask;
import com.richard.fyoung.customeradmin.workspace.task.mapper.AiAgentTaskMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 后台委派任务的管理台服务：列表 / 详情 / 取消。
 *
 * <p>不提供"新建任务"——任务由智能体在 ReAct 循环里通过 {@code agent_spawn} 自行派发，
 * 管理台是这批任务的观察与干预端，不是发起端。手工造一条任务记录并不会让任何东西真的跑起来。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    /** 列表接口返回的结果预览长度：够看清任务产出的开头，又不至于让一页十条撑成慢查询。 */
    private static final int RESULT_PREVIEW_LENGTH = 200;

    private final AiAgentTaskMapper taskMapper;
    private final TaskRepository taskRepository;

    public AgentTaskService(AiAgentTaskMapper taskMapper, TaskRepository taskRepository) {
        this.taskMapper = taskMapper;
        this.taskRepository = taskRepository;
    }

    public IPage<AgentTaskVO> page(AgentTaskPageQuery query) {
        LambdaQueryWrapper<AiAgentTask> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(AiAgentTask::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getAgentCode())) {
            wrapper.eq(AiAgentTask::getParentAgentCode, query.getAgentCode());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(AiAgentTask::getTaskId, query.getKeyword())
                .or().like(AiAgentTask::getSubAgentId, query.getKeyword())
                .or().like(AiAgentTask::getParentSessionId, query.getKeyword()));
        }
        // 按主键倒序而不是 createdAt：同一毫秒创建的多条任务用时间排会不稳定，翻页会看到重复/遗漏
        wrapper.orderByDesc(AiAgentTask::getId);

        IPage<AiAgentTask> page = taskMapper.selectPage(new Page<>(query.getCurrent(), query.getSize()), wrapper);
        return page.convert(record -> toVo(record, true));
    }

    /** 任务详情：结果与错误信息取全文。 */
    public AgentTaskVO get(String taskId) {
        return toVo(requireTask(taskId), false);
    }

    /**
     * 取消任务：委托 {@link TaskRepository#cancelTask}，由它同时落取消标志与中断执行线程。
     *
     * <p>已是终态的任务也照常放行（返回后前端刷新即可看到实际状态）——取消是幂等操作，
     * 为"刚好在点击瞬间跑完"这种正常竞态报错没有意义。</p>
     */
    public void cancel(String taskId) {
        AiAgentTask task = requireTask(taskId);
        taskRepository.cancelTask(RuntimeContext.empty(), task.getParentSessionId(), taskId);
        log.info("[agent-task] cancel requested from console: taskId={} agentCode={}",
            taskId, task.getParentAgentCode());
    }

    private AiAgentTask requireTask(String taskId) {
        AiAgentTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiAgentTask>()
            .eq(AiAgentTask::getTaskId, taskId).last("LIMIT 1"));
        if (task == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return task;
    }

    /**
     * DO → VO。
     *
     * @param preview true=列表模式，结果截断；false=详情模式，结果全文
     */
    private AgentTaskVO toVo(AiAgentTask record, boolean preview) {
        AgentTaskVO vo = new AgentTaskVO();
        vo.setId(record.getId());
        vo.setTaskId(record.getTaskId());
        vo.setParentAgentCode(record.getParentAgentCode());
        vo.setSubAgentId(record.getSubAgentId());
        vo.setParentSessionId(record.getParentSessionId());
        vo.setStatus(record.getStatus());
        vo.setErrorMessage(record.getErrorMessage());
        vo.setCancelRequested(record.getCancelRequested());
        vo.setCreatedAt(record.getCreatedAt());
        vo.setStartedAt(record.getStartedAt());
        vo.setFinishedAt(record.getFinishedAt());

        String result = record.getResult();
        if (preview && result != null && result.length() > RESULT_PREVIEW_LENGTH) {
            vo.setResult(result.substring(0, RESULT_PREVIEW_LENGTH));
            vo.setResultTruncated(true);
        } else {
            vo.setResult(result);
        }
        if (record.getStartedAt() != null && record.getFinishedAt() != null) {
            vo.setCostMs(Duration.between(record.getStartedAt(), record.getFinishedAt()).toMillis());
        }
        return vo;
    }

    /** 状态枚举取值，供前端下拉与参数校验对齐（避免前后端各写一份字符串常量）。 */
    public static String[] statusOptions() {
        TaskStatus[] values = TaskStatus.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name();
        }
        return names;
    }
}
