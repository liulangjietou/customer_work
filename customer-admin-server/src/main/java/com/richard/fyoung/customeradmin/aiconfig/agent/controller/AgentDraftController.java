package com.richard.fyoung.customeradmin.aiconfig.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentDraftService;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 个人草稿入口；用户 ID 只从登录态派生，配置管理权限仍由原 agent 权限控制。 */
@Validated
@RestController
@RequestMapping("/api/aiconfig/agent-drafts")
@SaCheckPermission("agent:view")
public class AgentDraftController {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final AgentDraftService drafts;

    public AgentDraftController(AgentDraftService drafts) {
        this.drafts = drafts;
    }

    /** 只有具备配置写权限的用户才能读取自己的草稿。 */
    @GetMapping
    @SaCheckPermission(value = {"agent:add", "agent:edit"}, mode = SaMode.OR)
    public Result<List<AgentDraftVO>> list() {
        return Result.success(drafts.list(StpUtil.getLoginIdAsLong()));
    }

    /** 恢复不会写入可运行智能体。 */
    @GetMapping("/{id}")
    @SaCheckPermission(value = {"agent:add", "agent:edit"}, mode = SaMode.OR)
    public Result<AgentDraftVO> get(@PathVariable @Pattern(regexp = UUID_PATTERN) String id) {
        return Result.success(drafts.get(id, StpUtil.getLoginIdAsLong()));
    }

    /** 区分新建权限与编辑权限，草稿不增加任何资源授权。 */
    @PutMapping("/{id}")
    public Result<AgentDraftVO> save(@PathVariable @Pattern(regexp = UUID_PATTERN) String id,
                                    @Valid @RequestBody AgentDraftSaveRequest request) {
        StpUtil.checkPermission(request.agentId() == null ? "agent:add" : "agent:edit");
        return Result.success(drafts.save(id, StpUtil.getLoginIdAsLong(), request));
    }

    /** 删除只作用于当前用户，版本参数防止并发误删。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = {"agent:add", "agent:edit"}, mode = SaMode.OR)
    public Result<Void> delete(@PathVariable @Pattern(regexp = UUID_PATTERN) String id,
                               @RequestParam @Positive long expectedVersion) {
        drafts.delete(id, StpUtil.getLoginIdAsLong(), expectedVersion);
        return Result.success();
    }
}
