package com.richard.fyoung.customeradmin.workspace.task.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.task.dto.AgentTaskPageQuery;
import com.richard.fyoung.customeradmin.workspace.task.dto.AgentTaskVO;
import com.richard.fyoung.customeradmin.workspace.task.service.AgentTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体后台委派任务：列表 / 详情 / 取消 / 状态字典。
 *
 * <p>没有新增与删除接口：任务由智能体自己派发（{@code agent_spawn} 异步模式），管理台只做观察与干预；
 * 删除历史会让"这个任务当时跑成什么样"永久失考，与任务记录的留证定位相悖。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/agent-task")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;

    public AgentTaskController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @SaCheckPermission("agent-task:view")
    @GetMapping("/page")
    public Result<IPage<AgentTaskVO>> page(AgentTaskPageQuery query) {
        return Result.success(agentTaskService.page(query));
    }

    @SaCheckPermission("agent-task:view")
    @GetMapping("/{taskId}")
    public Result<AgentTaskVO> get(@PathVariable String taskId) {
        return Result.success(agentTaskService.get(taskId));
    }

    /** 状态字典：前端筛选下拉直接用，避免前后端各维护一份状态字符串。 */
    @SaCheckPermission("agent-task:view")
    @GetMapping("/statuses")
    public Result<String[]> statuses() {
        return Result.success(AgentTaskService.statusOptions());
    }

    @SaCheckPermission("agent-task:cancel")
    @OperationLog(operation = "取消后台任务", target = "ai_agent_task")
    @PostMapping("/{taskId}/cancel")
    public Result<Void> cancel(@PathVariable String taskId) {
        agentTaskService.cancel(taskId);
        return Result.success();
    }
}
