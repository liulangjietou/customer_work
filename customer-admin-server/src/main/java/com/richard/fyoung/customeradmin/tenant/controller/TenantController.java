package com.richard.fyoung.customeradmin.tenant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.access.dto.TenantAccessDeliveryVO;
import com.richard.fyoung.customeradmin.tenant.dto.TenantPageQuery;
import com.richard.fyoung.customeradmin.tenant.dto.TenantSaveRequest;
import com.richard.fyoung.customeradmin.tenant.dto.TenantVO;
import com.richard.fyoung.customeradmin.tenant.dto.TenantViewVO;
import com.richard.fyoung.customeradmin.tenant.entity.TenantStatus;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户管理：CRUD + 生命周期 + 控制面视角切换。
 *
 * <p>整个 Controller 是控制面专属。两道防线：{@code tenant:*} 权限点在租户开通时被显式排除，
 * 不会落到租户管理员的角色上；每个方法再校验一次调用者具备显式控制面能力。
 * 后者不是冗余——权限点是通用 RBAC，可能被误配置，而租户列表泄露的是全体客户名单。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    private final TenantService tenantService;
    private final CrossTenantAuthority crossTenantAuthority;

    public TenantController(TenantService tenantService, CrossTenantAuthority crossTenantAuthority) {
        this.tenantService = tenantService;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    @SaCheckPermission("tenant:view")
    @GetMapping("/page")
    public Result<PageResult<TenantVO>> page(TenantPageQuery query) {
        assertCrossTenantAuthority();
        return Result.success(tenantService.page(query));
    }

    @SaCheckPermission("tenant:view")
    @GetMapping("/{id}")
    public Result<TenantVO> get(@PathVariable Long id) {
        assertCrossTenantAuthority();
        return Result.success(tenantService.get(id));
    }

    /** 可切换的租户下拉（控制面顶部租户切换器的数据源）。 */
    @SaCheckPermission("tenant:view")
    @GetMapping("/options")
    public Result<List<TenantVO>> options() {
        assertCrossTenantAuthority();
        return Result.success(tenantService.listActive());
    }

    @SaCheckPermission("tenant:add")
    @OperationLog(operation = "新增租户", target = "sys_tenant")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody TenantSaveRequest request) {
        assertCrossTenantAuthority();
        return Result.success(tenantService.create(request));
    }

    @SaCheckPermission("tenant:edit")
    @OperationLog(operation = "编辑租户", target = "sys_tenant")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody TenantSaveRequest request) {
        assertCrossTenantAuthority();
        tenantService.update(request);
        return Result.success();
    }

    /** 冻结 / 恢复 / 退租：只改状态不动数据。 */
    @SaCheckPermission("tenant:edit")
    @OperationLog(operation = "变更租户状态", target = "sys_tenant")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam String status) {
        assertCrossTenantAuthority();
        tenantService.changeStatus(id, TenantStatus.parse(status));
        return Result.success();
    }

    /** 不改租户状态，主动让该租户全部后台会话失效并发布新访问版本。 */
    @SaCheckPermission("tenant:edit")
    @OperationLog(operation = "撤销租户会话", target = "sys_tenant")
    @PostMapping("/{id}/revoke-sessions")
    public Result<Void> revokeSessions(@PathVariable Long id) {
        assertCrossTenantAuthority();
        tenantService.revokeSessions(id);
        return Result.success();
    }

    /** 最近一次租户访问快照的可靠投递状态。 */
    @SaCheckPermission("tenant:view")
    @GetMapping("/{id}/access-delivery")
    public Result<TenantAccessDeliveryVO> accessDelivery(@PathVariable Long id) {
        assertCrossTenantAuthority();
        return Result.success(tenantService.latestAccessDelivery(id));
    }

    @SaCheckPermission("tenant:delete")
    @OperationLog(operation = "租户退租", target = "sys_tenant")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        assertCrossTenantAuthority();
        tenantService.delete(id);
        return Result.success();
    }

    /**
     * 当前视角信息：前端据此决定是否渲染租户切换器、当前停在哪个租户。
     * 不加权限点——任何登录用户都要知道自己在哪个租户下，租户管理员拿到的永远是自己那一个。
     */
    @GetMapping("/current-view")
    public Result<TenantViewVO> currentView() {
        TenantViewVO vo = new TenantViewVO();
        vo.setUserTenantId(TenantSession.currentUserTenant());
        vo.setEffectiveTenantId(TenantSession.effectiveTenant());
        vo.setCrossTenantAuthority(crossTenantAuthority.hasCurrentUserAuthority());
        return Result.success(vo);
    }

    /**
     * 控制面用户切换目标租户视角；传空回到自身租户视角。
     *
     * <p>切换只改自己会话里的一个值，不影响其他登录会话，也不改任何业务数据。</p>
     */
    @OperationLog(operation = "切换租户视角", target = "sys_tenant")
    @SaCheckPermission("tenant:view")
    @PutMapping("/switch-view")
    public Result<Void> switchView(@RequestParam(required = false) String tenantCode) {
        assertCrossTenantAuthority();
        TenantAccessSnapshot snapshot = null;
        if (tenantCode != null && !tenantCode.isBlank()) {
            snapshot = tenantService.resolveAccessibleSnapshot(tenantCode);
            if (snapshot == null) {
                throw new BizException(ResultCode.TENANT_NOT_FOUND);
            }
        }
        if (snapshot == null) {
            TenantSession.clearView();
        } else {
            TenantSession.switchView(snapshot.tenantId(), snapshot.accessEpoch());
        }
        return Result.success();
    }

    private void assertCrossTenantAuthority() {
        if (!crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.TENANT_VIEW_FORBIDDEN);
        }
    }
}
