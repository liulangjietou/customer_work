package com.richard.fyoung.customerwork.observability.analytics;

import java.util.Map;

/**
 * 人机切换维度业务统计（窗口内活动 + 当前积压快照）。
 *
 * <p><b>诚实边界</b>：不提供"转人工率"（handoff 总量 / 会话总量）——当前没有可靠的"时间窗内会话总量"
 * 数据源（{@code SessionStateManager} 只能列举当前仍存续的会话，已结束会话已从状态存储中删除，
 * 无法反推历史某窗口内开启过的会话数），编造一个不可靠的分母不如不给。本统计只给 handoff 总量
 * 作为业务活动量的直接信号。</p>
 *
 * @param totalInWindow            窗口内创建的工单总数
 * @param countByStatus            窗口内按状态分布（PENDING/CLAIMED/RESOLVED → 数量）
 * @param avgTimeToClaimSeconds    窗口内已接单工单的平均"创建→接单"时长（秒）；窗口内无已接单工单时为 {@code null}
 * @param avgTimeToResolveSeconds  窗口内已结案工单的平均"接单→结案"时长（秒）；窗口内无已结案工单时为 {@code null}
 * @param currentPendingBacklog    当前（不限时间窗）待接单积压数
 * @param currentClaimedBacklog    当前（不限时间窗）处理中（已接单未结案）积压数
 * @author owlzhangfq@gmail.com
 */
public record HandoffStats(
    long totalInWindow,
    Map<String, Long> countByStatus,
    Double avgTimeToClaimSeconds,
    Double avgTimeToResolveSeconds,
    long currentPendingBacklog,
    long currentClaimedBacklog
) {
}
