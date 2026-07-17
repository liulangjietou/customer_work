package com.richard.fyoung.customerwork.memory;

/**
 * 事实日志记录（查询侧只读视图，含 {@link FactLog#read} 未暴露的时间戳）。
 *
 * @param ts     写入时间戳（毫秒）
 * @param tenant 租户 ID
 * @param fact   事实内容原文
 * @author owlzhangfq@gmail.com
 */
public record FactRecord(long ts, String tenant, String fact) {
}
