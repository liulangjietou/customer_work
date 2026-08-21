package com.richard.fyoung.customerwork.safety.tenant;

/**
 * 历史租户归并的唯一兼容常量源。
 *
 * <p>该值不是可用租户，也不能写入 {@link TenantContext}。它只允许用于读取升级前遗留的
 * Redis、Nacos、MinIO 与会话分区；确认最长 Redis 窗口过期、外部对象迁完且旧客户端全部退场后，
 * 应连同各读取兼容分支一起删除。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class LegacyTenantCompatibility {

    /** V7 / V63 之前的历史租户编码，仅供迁移兼容读取。 */
    public static final String PLATFORM_TENANT_ID = "__platform__";

    private LegacyTenantCompatibility() {
    }
}
