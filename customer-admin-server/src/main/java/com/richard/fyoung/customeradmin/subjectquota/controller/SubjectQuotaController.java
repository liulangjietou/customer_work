package com.richard.fyoung.customeradmin.subjectquota.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.subjectquota.dto.AdminQuotaUserVO;
import com.richard.fyoung.customeradmin.subjectquota.dto.AdminUserLevelSaveRequest;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaHitVO;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaLevelSaveRequest;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaLevelVO;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaUserVO;
import com.richard.fyoung.customeradmin.subjectquota.dto.UserLevelSaveRequest;
import com.richard.fyoung.customeradmin.subjectquota.service.SubjectQuotaAdminService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHitRank;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主体级速率配额管理：等级维护、用户分档、超限命中查看。
 *
 * <p><b>租户取当前视角</b>（{@link TenantSession#effectiveTenant()}）而不是让前端传：
 * 等级与用户名单都是租户内数据，让参数决定读哪个租户，等于把越权做成了一个查询参数。
 * 控制面用户要看别的租户，走已有的"切换视角"，角色与权限点校验在那一步完成。</p>
 *
 * <p>与租户配额（{@code /api/billing/quota}）的分工见 {@code SubjectQuotaGuard} 类注释：
 * 那边是计费上限，这边是防滥用闸门，两者同时生效。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/subject-quota")
public class SubjectQuotaController {

    private final SubjectQuotaAdminService service;
    private final CrossTenantAuthority crossTenantAuthority;

    public SubjectQuotaController(SubjectQuotaAdminService service,
                                  CrossTenantAuthority crossTenantAuthority) {
        this.service = service;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    // ---------- 等级 ----------

    @SaCheckPermission("subject-quota:view")
    @GetMapping("/levels")
    public Result<List<SubjectQuotaLevelVO>> listLevels() {
        return Result.success(service.listLevels(TenantSession.effectiveTenant()));
    }

    @SaCheckPermission("subject-quota:level-edit")
    @OperationLog(operation = "保存配额等级", target = "cw_subject_quota_level")
    @PostMapping("/levels")
    public Result<Void> saveLevel(@Valid @RequestBody SubjectQuotaLevelSaveRequest request) {
        String tenantId = TenantSession.effectiveTenant();
        requireDefaultBaselineAuthority(tenantId);
        service.saveLevel(tenantId, request);
        return Result.success();
    }

    @SaCheckPermission("subject-quota:level-edit")
    @OperationLog(operation = "删除配额等级", target = "cw_subject_quota_level")
    @DeleteMapping("/levels")
    public Result<Void> deleteLevel(@RequestParam String levelCode) {
        String tenantId = TenantSession.effectiveTenant();
        requireDefaultBaselineAuthority(tenantId);
        service.deleteLevel(tenantId, levelCode);
        return Result.success();
    }

    // ---------- 用户分档 ----------

    @SaCheckPermission("subject-quota:view")
    @GetMapping("/users")
    public Result<PageResult<SubjectQuotaUserVO>> pageUsers(PageQuery query) {
        return Result.success(service.pageUsers(TenantSession.effectiveTenant(), query));
    }

    @SaCheckPermission("subject-quota:user-edit")
    @OperationLog(operation = "分配用户配额等级", target = "cw_user")
    @PostMapping("/users/level")
    public Result<Void> assignUserLevel(@Valid @RequestBody UserLevelSaveRequest request) {
        service.assignUserLevel(TenantSession.effectiveTenant(), request.getUserId(), request.getLevelCode());
        return Result.success();
    }

    // ---------- 后台用户分档 ----------

    @SaCheckPermission("subject-quota:view")
    @GetMapping("/admin-users")
    public Result<PageResult<AdminQuotaUserVO>> pageAdminUsers(PageQuery query) {
        return Result.success(service.pageAdminUsers(query));
    }

    @SaCheckPermission("subject-quota:user-edit")
    @OperationLog(operation = "分配后台用户配额等级", target = "sys_user")
    @PostMapping("/admin-users/level")
    public Result<Void> assignAdminUserLevel(@Valid @RequestBody AdminUserLevelSaveRequest request) {
        service.assignAdminUserLevel(TenantSession.effectiveTenant(),
            request.getUserId(), request.getLevelCode());
        return Result.success();
    }

    // ---------- 命中记录 ----------

    /** 超限命中明细。默认回看 24 小时——再长就该去查日志而不是翻页面。 */
    @SaCheckPermission("subject-quota:view")
    @GetMapping("/hits")
    public Result<List<SubjectQuotaHitVO>> listHits(@RequestParam(defaultValue = "24") int hours,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return Result.success(service.listHits(TenantSession.effectiveTenant(), hours, limit));
    }

    /** 超限命中排行（谁在刷）。 */
    @SaCheckPermission("subject-quota:view")
    @GetMapping("/hits/rank")
    public Result<List<SubjectQuotaHitRank>> rankHits(@RequestParam(defaultValue = "24") int hours,
                                                      @RequestParam(defaultValue = "20") int limit) {
        return Result.success(service.rankHits(TenantSession.effectiveTenant(), hours, limit));
    }

    /** default 档位是所有业务租户的共享回退基线，写它必须额外具备控制面身份。 */
    private void requireDefaultBaselineAuthority(String tenantId) {
        if (TenantContext.isDefaultTenant(tenantId)) {
            crossTenantAuthority.requireCurrentUserAuthority();
        }
    }
}
