package com.richard.fyoung.customeradmin.governance.change.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.governance.change.dto.GovernanceAuditEventVO;
import com.richard.fyoung.customeradmin.governance.change.dto.GovernanceDecisionRequest;
import com.richard.fyoung.customeradmin.governance.change.dto.GovernedChangeVO;
import com.richard.fyoung.customeradmin.governance.change.service.GovernedChangeService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 高风险变更复核、拒绝与审计查询 API。 */
@RestController
@RequestMapping("/api/governance/changes")
public class GovernedChangeController {

    private final GovernedChangeService service;
    private final CrossTenantAuthority crossTenantAuthority;

    public GovernedChangeController(GovernedChangeService service,
                                    CrossTenantAuthority crossTenantAuthority) {
        this.service = service;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    @SaCheckPermission("governance:view")
    @GetMapping
    public Result<List<GovernedChangeVO>> list(@RequestParam(required = false) String status) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(service.list(status));
    }

    @SaCheckPermission("governance:view")
    @GetMapping("/{id}/audit")
    public Result<List<GovernanceAuditEventVO>> audit(@PathVariable String id) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(service.audit(id));
    }

    @SaCheckPermission("governance:approve")
    @OperationLog(operation = "批准高风险变更", target = "ai_governed_change_request")
    @PostMapping("/{id}/approve")
    public Result<GovernedChangeVO> approve(@PathVariable String id,
                                            @Valid @RequestBody GovernanceDecisionRequest request) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(service.approve(id, StpUtil.getLoginIdAsLong(), username(), request.reason()));
    }

    @SaCheckPermission("governance:approve")
    @OperationLog(operation = "拒绝高风险变更", target = "ai_governed_change_request")
    @PostMapping("/{id}/reject")
    public Result<GovernedChangeVO> reject(@PathVariable String id,
                                           @Valid @RequestBody GovernanceDecisionRequest request) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(service.reject(id, StpUtil.getLoginIdAsLong(), username(), request.reason()));
    }

    private String username() {
        return StpUtil.getTokenSession().getString("username");
    }
}
