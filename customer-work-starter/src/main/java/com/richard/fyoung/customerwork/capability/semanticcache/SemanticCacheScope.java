package com.richard.fyoung.customerwork.capability.semanticcache;

/**
 * 一个缓存分区及其条目数（运营看板的分区选择器用）。
 *
 * <p><b>为什么需要它</b>：语义缓存的分区键是数据隔离键（由 {@code TenantResolver} 从 sessionId 前缀解析，
 * 用户端解析出来的是 {@code u{userId}} 这样的用户标识）。这个隔离粒度是刻意的——两个用户问同一句话
 * 答案未必相同，按用户分区是安全底线，不能像 CSAT 那样改成租户级。</p>
 *
 * <p>但代价是运营在看板上根本猜不到该填什么：他既不知道有哪些分区，也不知道哪个分区里有东西。
 * 与其让人手填一个猜不到的用户 ID，不如把实际存在的分区列出来给他选。</p>
 *
 * @param scopeId 分区键
 * @param entries 该分区当前的缓存条目数——运营据此判断哪个分区值得点进去看
 * @author owlzhangfq@gmail.com
 */
public record SemanticCacheScope(String scopeId, long entries) {
}
