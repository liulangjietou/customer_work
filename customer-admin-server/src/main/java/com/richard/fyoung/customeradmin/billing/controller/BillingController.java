package com.richard.fyoung.customeradmin.billing.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaSaveRequest;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaVO;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.service.BillingReportService;
import com.richard.fyoung.customeradmin.billing.service.ModelPriceAdminService;
import com.richard.fyoung.customeradmin.billing.service.TenantQuotaService;
import com.richard.fyoung.customeradmin.billing.service.UsageAggregationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 配额与计费：租户配额维护、模型单价维护、账单报表、手工归集。
 *
 * <p>权限分层：账单明细（{@code billing:view}）租户管理员也能看自己那份；
 * 配额与单价的编辑、以及跨租户总览要求控制面角色，额外校验一次身份——
 * 全租户消费明细泄露的是全体客户名单与用量，不能只靠权限点配置正确。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final TenantQuotaService quotaService;
    private final ModelPriceAdminService priceService;
    private final BillingReportService reportService;
    private final UsageAggregationService aggregationService;
    private final CrossTenantAuthority crossTenantAuthority;

    public BillingController(TenantQuotaService quotaService,
                             ModelPriceAdminService priceService,
                             BillingReportService reportService,
                             UsageAggregationService aggregationService,
                             CrossTenantAuthority crossTenantAuthority) {
        this.quotaService = quotaService;
        this.priceService = priceService;
        this.reportService = reportService;
        this.aggregationService = aggregationService;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    // ---------- 租户配额 ----------

    @SaCheckPermission("billing:view")
    @GetMapping("/quota")
    public Result<List<TenantQuotaVO>> listQuota(@RequestParam String tenantId) {
        assertCrossTenantAuthority();
        return Result.success(quotaService.listByTenant(tenantId));
    }

    @SaCheckPermission("billing:quota-edit")
    @OperationLog(operation = "保存租户配额", target = "cw_tenant_quota")
    @PostMapping("/quota")
    public Result<Void> saveQuota(@Valid @RequestBody TenantQuotaSaveRequest request) {
        assertCrossTenantAuthority();
        quotaService.save(request);
        return Result.success();
    }

    @SaCheckPermission("billing:quota-edit")
    @OperationLog(operation = "删除租户配额", target = "cw_tenant_quota")
    @DeleteMapping("/quota")
    public Result<Void> deleteQuota(@RequestParam String tenantId, @RequestParam String period) {
        assertCrossTenantAuthority();
        quotaService.delete(tenantId, period);
        return Result.success();
    }

    // ---------- 模型单价 ----------

    @SaCheckPermission("billing:view")
    @GetMapping("/price")
    public Result<List<AiModelPrice>> listPrice() {
        assertCrossTenantAuthority();
        return Result.success(priceService.list());
    }

    @SaCheckPermission("billing:price-edit")
    @OperationLog(operation = "新增模型单价", target = "ai_model_price")
    @PostMapping("/price")
    public Result<Long> createPrice(@Valid @RequestBody AiModelPrice request) {
        assertCrossTenantAuthority();
        return Result.success(priceService.create(request));
    }

    @SaCheckPermission("billing:price-edit")
    @OperationLog(operation = "删除模型单价", target = "ai_model_price")
    @DeleteMapping("/price/{id}")
    public Result<Void> deletePrice(@PathVariable Long id) {
        assertCrossTenantAuthority();
        priceService.delete(id);
        return Result.success();
    }

    // ---------- 账单 ----------

    /** 单租户账单：不传 tenantId 时取当前视角租户，租户管理员看到的恒是自己那份。 */
    @SaCheckPermission("billing:view")
    @GetMapping("/bill")
    public Result<List<UsageAggregate>> tenantBill(
        @RequestParam(required = false) String tenantId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // 指定别的租户属于跨租户读，必须具备控制面角色；接口权限点由注解继续校验
        if (tenantId != null && !tenantId.isBlank()
            && !TenantContext.sameTenant(tenantId, TenantSession.effectiveTenant())) {
            assertCrossTenantAuthority();
        }
        return Result.success(reportService.tenantBill(tenantId, from, to));
    }

    /** 跨租户账单总览（控制面专属）。 */
    @SaCheckPermission("billing:view")
    @GetMapping("/overview")
    public Result<List<UsageAggregate>> platformOverview(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        assertCrossTenantAuthority();
        return Result.success(reportService.platformOverview(from, to));
    }

    /**
     * 手工触发用量归集（补数据用）。
     *
     * <p>归集可重复执行：同一天再跑一次就覆盖，因此补数据只要重跑对应日期。</p>
     */
    @SaCheckPermission("billing:export")
    @OperationLog(operation = "手工归集用量", target = "cw_tenant_usage_daily")
    @PostMapping("/aggregate")
    public Result<Integer> aggregate(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        assertCrossTenantAuthority();
        return Result.success(aggregationService.aggregate(date));
    }

    private void assertCrossTenantAuthority() {
        if (!crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.TENANT_VIEW_FORBIDDEN);
        }
    }
}
