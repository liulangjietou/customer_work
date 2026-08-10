package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.tenant.CrossTenantOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 账单报表：按租户 / 按区间聚合 token 与金额。
 *
 * <p>数据源是归集表 {@code cw_tenant_usage_daily}，不是原始调用日志——金额在归集时就按当日单价
 * 算好落库了，查询时重算会让历史账单随调价而变动。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class BillingReportService {

    private final CwTenantUsageDailyMapper usageMapper;

    public BillingReportService(CwTenantUsageDailyMapper usageMapper) {
        this.usageMapper = usageMapper;
    }

    /**
     * 单租户账单明细（按模型分组）。
     *
     * <p>不传租户时取当前视角租户：运营方切到某租户就看那个租户的账，
     * 租户管理员则恒等于自己——一套接口两种身份都成立。</p>
     */
    public List<UsageAggregate> tenantBill(String tenantId, LocalDate from, LocalDate to) {
        String target = tenantId == null || tenantId.isBlank() ? TenantSession.effectiveTenant() : tenantId;
        if (target == null) {
            return List.of();
        }
        // 显式按 tenantId 查，跨租户豁免：运营方查别的租户时，当前上下文并不是目标租户
        return CrossTenantOperations.execute(
            () -> usageMapper.sumByTenantAndRange(target, from, to));
    }

    /**
     * 全租户账单总览（运营方专属）。
     *
     * <p>调用方必须先校验运营方身份——这是跨租户读，一旦被租户管理员调到就是全体客户的消费明细泄露。</p>
     */
    public List<UsageAggregate> platformOverview(LocalDate from, LocalDate to) {
        return CrossTenantOperations.execute(() -> usageMapper.sumGroupByTenant(from, to));
    }
}
