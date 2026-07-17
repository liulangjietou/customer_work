package com.richard.fyoung.customerwork.analytics;

import java.util.Map;

/**
 * 审批维度业务统计（窗口内活动 + 当前积压快照）。
 *
 * @param totalInWindow      窗口内创建的审批单总数
 * @param countByStatus      窗口内按状态分布（PENDING/APPROVED/DENIED → 数量）
 * @param approvalRate       放行率 = APPROVED / (APPROVED + DENIED)；两者皆 0 时记 0.0（非 NaN）
 * @param avgDecisionSeconds 窗口内已决策单的平均决策时长（秒）；窗口内无已决策单时为 {@code null}
 * @param currentPendingBacklog 当前（不限时间窗）待决积压数——运维关注的"现在有多少单卡着"快照指标
 * @author owlzhangfq@gmail.com
 */
public record ApprovalStats(
    long totalInWindow,
    Map<String, Long> countByStatus,
    double approvalRate,
    Double avgDecisionSeconds,
    long currentPendingBacklog
) {
}
