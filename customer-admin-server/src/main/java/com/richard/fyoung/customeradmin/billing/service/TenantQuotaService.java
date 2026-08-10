package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.config.QuotaGatewayProvider;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaSaveRequest;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaVO;
import com.richard.fyoung.customerwork.safety.quota.MybatisTenantQuotaStore;
import com.richard.fyoung.customerwork.safety.quota.QuotaExceedAction;
import com.richard.fyoung.customerwork.safety.quota.QuotaPeriod;
import com.richard.fyoung.customerwork.safety.quota.TenantQuota;
import com.richard.fyoung.customerwork.safety.quota.TenantQuotaStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台的租户配额维护：直接读写客服端库的 {@code cw_tenant_quota}，客服端轮询/实时读取即生效。
 *
 * <p>复用 starter 的 {@link MybatisTenantQuotaStore} 而不是重写一套 CRUD——同一张表、同一套语义，
 * 重写只会多出一份要同步维护的代码（照内容风控"写侧复用 starter Mapper"的先例）。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class TenantQuotaService {

    private final QuotaGatewayProvider gatewayProvider;

    public TenantQuotaService(QuotaGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    /** 每次取新的 Store：门面本身是惰性缓存的，这里只是把它包成 Store 语义，无额外开销。 */
    private TenantQuotaStore store() {
        return new MybatisTenantQuotaStore(gatewayProvider.get().quotaMapper());
    }

    public List<TenantQuotaVO> listByTenant(String tenantId) {
        return store().findByTenant(tenantId).stream().map(TenantQuotaService::toVO).toList();
    }

    public void save(TenantQuotaSaveRequest request) {
        TenantQuota quota = new TenantQuota(
            request.getTenantId(),
            QuotaPeriod.parse(request.getPeriod()),
            request.getTokenLimit() == null ? 0L : request.getTokenLimit(),
            request.getAmountLimit() == null ? BigDecimal.ZERO : request.getAmountLimit(),
            QuotaExceedAction.parse(request.getExceedAction()),
            request.getWarnPercent() == null ? 80 : request.getWarnPercent(),
            request.getEnabled() == null || request.getEnabled());
        store().save(quota);
        log.info("tenant quota saved, tenant={}, period={}, tokenLimit={}, action={}",
            quota.tenantId(), quota.period(), quota.tokenLimit(), quota.exceedAction());
    }

    public void delete(String tenantId, String period) {
        store().delete(tenantId, QuotaPeriod.parse(period));
        log.info("tenant quota deleted, tenant={}, period={}", tenantId, period);
    }

    private static TenantQuotaVO toVO(TenantQuota quota) {
        TenantQuotaVO vo = new TenantQuotaVO();
        vo.setTenantId(quota.tenantId());
        vo.setPeriod(quota.period().name());
        vo.setTokenLimit(quota.tokenLimit());
        vo.setAmountLimit(quota.amountLimit());
        vo.setExceedAction(quota.exceedAction().name());
        vo.setWarnPercent(quota.warnPercent());
        vo.setEnabled(quota.enabled());
        return vo;
    }
}
