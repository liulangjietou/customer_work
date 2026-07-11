package com.richard.fyoung.customerwork.handoff;

import lombok.Getter;

/**
 * 人机切换工单（充血：自带状态流转方法，非纯数据袋）。
 *
 * <p>由 {@code HumanHandoffTools.transferToHuman} 触发创建，取代此前"只打日志 + 生成随机字符串"
 * 的空实现——把转人工从一句话术升级为可查询、可流转的持久化实体。状态机由 {@link #claim}/{@link #resolve}
 * 在各自前置状态校验下推进，避免重复接单或未接单先结案。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class HandoffTicket {

    private final String id;
    private final String sessionId;
    private final String reason;
    private final long createdAtMs;

    private volatile HandoffStatus status = HandoffStatus.PENDING;
    private volatile String claimedBy;
    private volatile long claimedAtMs;
    private volatile String resolutionNote;
    private volatile long resolvedAtMs;

    public HandoffTicket(String id, String sessionId, String reason, long createdAtMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.reason = reason;
        this.createdAtMs = createdAtMs;
    }

    /** 坐席接单：仅 PENDING 可推进，否则 fast-fail 拒绝重复接单。 */
    public void claim(String operator, long whenMs) {
        if (status != HandoffStatus.PENDING) {
            throw new IllegalStateException("handoff already claimed or resolved: id=" + id + ", status=" + status);
        }
        this.status = HandoffStatus.CLAIMED;
        this.claimedBy = operator;
        this.claimedAtMs = whenMs;
    }

    /** 坐席处理完毕、回收给 AI：仅 CLAIMED 可推进，否则 fast-fail 拒绝未接单先结案。 */
    public void resolve(String note, long whenMs) {
        if (status != HandoffStatus.CLAIMED) {
            throw new IllegalStateException("handoff not claimed yet, cannot resolve: id=" + id + ", status=" + status);
        }
        this.status = HandoffStatus.RESOLVED;
        this.resolutionNote = note;
        this.resolvedAtMs = whenMs;
    }

    /**
     * 供持久化存储层（如 {@link JdbcHandoffStore}）从数据源重建已有工单——跳过
     * {@link #claim}/{@link #resolve} 的业务状态机校验（这不是一次新的坐席动作，只是把已发生过的
     * 流转结果读回内存）。包级可见，仅供本包内的 {@link HandoffStore} 实现使用。
     */
    static HandoffTicket reconstruct(String id, String sessionId, String reason, long createdAtMs,
                                     HandoffStatus status, String claimedBy, long claimedAtMs,
                                     String resolutionNote, long resolvedAtMs) {
        HandoffTicket ticket = new HandoffTicket(id, sessionId, reason, createdAtMs);
        ticket.status = status;
        ticket.claimedBy = claimedBy;
        ticket.claimedAtMs = claimedAtMs;
        ticket.resolutionNote = resolutionNote;
        ticket.resolvedAtMs = resolvedAtMs;
        return ticket;
    }
}
