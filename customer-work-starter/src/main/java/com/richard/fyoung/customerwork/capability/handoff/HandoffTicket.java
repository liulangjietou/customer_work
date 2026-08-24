package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketStatus;
import lombok.Getter;

/**
 * 旧人机切换 API 的三态兼容读模型。
 *
 * <p>由 {@code HumanHandoffTools.transferToHuman} 触发创建，取代此前"只打日志 + 生成随机字符串"
 * 的空实现。P1-03 起生产状态由 {@link Ticket} 统一承载，本类型只投影 PENDING/CLAIMED/RESOLVED；
 * {@link #claim}/{@link #resolve} 仅供旧表迁移回归测试使用。</p>
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

    // ---- 智能路由中控·工单智能分配增强字段（全部可空、建单后异步回写，向后兼容旧数据不迁移）----
    /** 工单分类（LLM 分类，如 退款/物流/投诉）。 */
    private volatile String category;
    /** 所需坐席技能标签（LLM 分类，可空表示无硬性要求）。 */
    private volatile String requiredSkill;
    /** 优先级枚举名（LOW/MEDIUM/HIGH/URGENT）。 */
    private volatile String priority;
    /** 用户情绪（LLM 分类）。 */
    private volatile String emotion;
    /** 推荐坐席列表（JSON，{@code SeatRecommendation} 序列化；HITL 供坐席点选，非自动派单）。 */
    private volatile String suggestedAssignees;

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
     * 智能路由中控·工单智能分配：建单后由 {@code HandoffCreatedEnricher} 异步回写分类与推荐结果。
     *
     * <p>纯字段赋值、不触碰状态机（分类/推荐是旁挂增强，与 PENDING→CLAIMED→RESOLVED 主流转无关），全部可空。</p>
     */
    public void applyRoutingSuggestion(String category, String requiredSkill, String priority,
                                       String emotion, String suggestedAssignees) {
        this.category = category;
        this.requiredSkill = requiredSkill;
        this.priority = priority;
        this.emotion = emotion;
        this.suggestedAssignees = suggestedAssignees;
    }

    /**
     * 将权威 {@link Ticket} 投影为旧 /handoffs API 的三态读模型。
     *
     * <p>此对象不再承担生产持久化；兼容 API 需要的字段全部来自同一张 cw_ticket，因而创建、接单、
     * 结案和智能路由不会再出现双表状态分叉。</p>
     */
    public static HandoffTicket fromTicket(Ticket ticket) {
        HandoffStatus projectedStatus = projectStatus(ticket.getStatus());
        long createdAt = ticket.getHandoffAtMs() > 0 ? ticket.getHandoffAtMs() : ticket.getCreatedAtMs();
        long resolvedAt = ticket.getResolvedAtMs() > 0 ? ticket.getResolvedAtMs() : ticket.getClosedAtMs();
        return reconstruct(ticket.getId(), ticket.getSessionId(), ticket.getHandoffReason(), createdAt,
            projectedStatus, ticket.getAssignee(), ticket.getClaimedAtMs(), ticket.getResolveNote(),
            resolvedAt, ticket.getRoutingCategory(), ticket.getRequiredSkill(), ticket.getRoutingPriority(),
            ticket.getEmotion(),
            ticket.getSuggestedAssignees());
    }

    private static HandoffStatus projectStatus(TicketStatus status) {
        return switch (status) {
            case WAITING_AGENT -> HandoffStatus.PENDING;
            case PROCESSING, ON_HOLD, WAITING_CONFIRM -> HandoffStatus.CLAIMED;
            case RESOLVED, CLOSED -> HandoffStatus.RESOLVED;
            case AI_SERVING -> throw new IllegalArgumentException("AI_SERVING is not a handoff ticket");
        };
    }

    /**
     * 向后兼容重载（无智能分配增强字段）：路由相关列一律置空。保留给未涉及增强字段的既有调用方/测试，
     * 避免签名变更的连锁改动。
     */
    static HandoffTicket reconstruct(String id, String sessionId, String reason, long createdAtMs,
                                     HandoffStatus status, String claimedBy, long claimedAtMs,
                                     String resolutionNote, long resolvedAtMs) {
        return reconstruct(id, sessionId, reason, createdAtMs, status, claimedBy, claimedAtMs,
            resolutionNote, resolvedAtMs, null, null, null, null, null);
    }

    /**
     * 供持久化存储层（如 {@link MybatisHandoffStore}）从数据源重建已有工单——跳过
     * {@link #claim}/{@link #resolve} 的业务状态机校验（这不是一次新的坐席动作，只是把已发生过的
     * 流转结果读回内存）。包级可见，仅供本包内的 {@link HandoffStore} 实现使用。
     */
    static HandoffTicket reconstruct(String id, String sessionId, String reason, long createdAtMs,
                                     HandoffStatus status, String claimedBy, long claimedAtMs,
                                     String resolutionNote, long resolvedAtMs,
                                     String category, String requiredSkill, String priority,
                                     String emotion, String suggestedAssignees) {
        HandoffTicket ticket = new HandoffTicket(id, sessionId, reason, createdAtMs);
        ticket.status = status;
        ticket.claimedBy = claimedBy;
        ticket.claimedAtMs = claimedAtMs;
        ticket.resolutionNote = resolutionNote;
        ticket.resolvedAtMs = resolvedAtMs;
        ticket.category = category;
        ticket.requiredSkill = requiredSkill;
        ticket.priority = priority;
        ticket.emotion = emotion;
        ticket.suggestedAssignees = suggestedAssignees;
        return ticket;
    }
}
