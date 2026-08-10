package com.richard.fyoung.customeradmin.billing.jdbc;

import com.richard.fyoung.customerwork.quota.mapper.TenantQuotaMapper;

/**
 * 客服端库上的配额数据门面。
 *
 * <p>配额表落在客服端库（运行时要读它来拦模型调用），后台通过跨库门面维护——
 * 与内容风控三表同一套路，见 {@code ContentGuardGateway}。</p>
 * @author owlzhangfq@gmail.com
 */
public record QuotaGateway(TenantQuotaMapper quotaMapper) {
}
