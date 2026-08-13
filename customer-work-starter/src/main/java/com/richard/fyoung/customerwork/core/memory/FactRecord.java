package com.richard.fyoung.customerwork.core.memory;

/**
 * 事实日志记录（查询侧只读视图，含 {@link FactLog#read} 未暴露的时间戳）。
 *
 * @param ts    写入时间戳（毫秒）
 * @param scope 记忆分区键（{@code TenantResolver} 由 sessionId 解析；<b>不是</b> SaaS 租户
 *              {@code TenantContext}，后者由持久层拦截器自动处理，不出现在本视图里）
 * @param fact  事实内容原文
 * @author owlzhangfq@gmail.com
 */
public record FactRecord(long ts, String scope, String fact) {
}
