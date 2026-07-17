package com.richard.fyoung.customerwork.analytics;

/**
 * 业务数据分析聚合报表（一次拉齐审批 / 人机切换 / 质检三个业务维度的运营视角）。
 *
 * <p>与故障诊断（{@code SessionDiagnostic}，按 sessionId 聚合单会话现场）互补：本报表按
 * <b>时间窗口</b>聚合，回答"这段时间业务运转得怎么样"而非"这一次请求出了什么问题"。</p>
 *
 * @param windowStartMs 统计窗口起点（毫秒时间戳，含）
 * @param windowEndMs   统计窗口终点（毫秒时间戳，不含）
 * @param approval      审批维度统计
 * @param handoff       人机切换维度统计
 * @param quality       质检维度统计（需显式指定租户才有数据，见 {@link QualityStats}）
 * @author owlzhangfq@gmail.com
 */
public record BusinessAnalyticsReport(
    long windowStartMs,
    long windowEndMs,
    ApprovalStats approval,
    HandoffStats handoff,
    QualityStats quality
) {
}
