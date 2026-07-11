package com.richard.fyoung.customeradmin.aiconfig.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体管理：CRUD + 关联表（MCP/Skill）维护 + 启用停用生命周期。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @SaCheckPermission("agent:view")
    @GetMapping
    public Result<PageResult<AgentVO>> page(PageQuery query) {
        return Result.success(agentService.page(query));
    }

    @SaCheckPermission("agent:view")
    @GetMapping("/{id}")
    public Result<AgentVO> get(@PathVariable Long id) {
        return Result.success(agentService.get(id));
    }

    @SaCheckPermission("agent:add")
    @OperationLog(operation = "新建智能体", target = "ai_agent")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody AgentSaveRequest request) {
        agentService.create(request);
        return Result.success();
    }

    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "编辑智能体", target = "ai_agent")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AgentSaveRequest request) {
        agentService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("agent:delete")
    @OperationLog(operation = "删除智能体", target = "ai_agent")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return Result.success();
    }

    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "启用智能体", target = "ai_agent")
    @PutMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        agentService.updateStatus(id, 1);
        return Result.success();
    }

    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "停用智能体", target = "ai_agent")
    @PutMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        agentService.updateStatus(id, 0);
        return Result.success();
    }
}
