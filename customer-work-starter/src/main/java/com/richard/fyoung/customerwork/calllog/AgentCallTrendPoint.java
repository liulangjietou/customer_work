package com.richard.fyoung.customerwork.calllog;

/**
 * 调用趋势聚合的一个时间桶。
 *
 * @param bucket        时间桶标签（按天为 {@code yyyy-MM-dd}，按小时为 {@code yyyy-MM-dd HH}）
 * @param count         该桶内调用数
 * @param avgDurationMs 该桶内平均总耗时（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record AgentCallTrendPoint(String bucket, long count, double avgDurationMs) {
}
