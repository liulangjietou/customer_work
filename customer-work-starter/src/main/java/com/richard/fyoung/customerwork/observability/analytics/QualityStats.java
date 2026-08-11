package com.richard.fyoung.customerwork.observability.analytics;

/**
 * 质检维度业务统计（按租户，窗口内质检失败事实聚合）。
 *
 * <p><b>诚实边界</b>：{@code FactLog} 是按租户分文件存储、无"列出全部租户"的能力，故本统计
 * <b>必须</b>显式指定 {@code tenantId} 才有数据；未指定时返回全零占位（{@link #tenantId} 为
 * {@code null}），而非编造一个不存在的"全租户汇总"。</p>
 *
 * @param tenantId          统计所属租户；未指定统计时为 {@code null}
 * @param failureCountInWindow 窗口内质检失败事实数
 * @param avgScore          窗口内质检失败事实的平均分；窗口内无失败事实或未指定租户时为 {@code null}
 * @author owlzhangfq@gmail.com
 */
public record QualityStats(
    String tenantId,
    long failureCountInWindow,
    Double avgScore
) {
}
