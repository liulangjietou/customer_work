package com.richard.fyoung.customerwork.data.ticket;

import lombok.Getter;

/**
 * 客服工单（充血实体：自带完整状态机流转方法，非纯数据袋）。
 *
 * <p>相较 {@code HandoffTicket} 的三态极简闭环，本实体承载一次会话从 AI 自助到人工受理、
 * 用户确认、结案与回流的完整生命周期。每个流转方法在推进前校验前置状态，非法流转直接
 * {@link IllegalStateException} fast-fail（避免"未接单先结案""已关闭又挂起"等脏流转）；
 * 成功流转则同步刷新对应状态字段与时间戳。状态字段用 {@code volatile} 保证跨线程可见性
 * （坐席工作台与 SLA 巡检可能并发读）。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class Ticket {

    private final String id;
    private final String sessionId;
    private final String userId;
    private final long createdAtMs;

    private volatile String title;
    private volatile TicketCategory category;
    private volatile TicketPriority priority;
    private volatile TicketStatus status;
    /** 当前处理坐席（WAITING_AGENT / AI_SERVING 时为 null）。 */
    private volatile String assignee;
    /** 转人工原因（最近一次）。 */
    private volatile String handoffReason;
    /** 坐席处理结论备注。 */
    private volatile String resolveNote;
    /** 智能路由分类原文（与受控枚举 category 分离，允许业务侧自定义分类标签）。 */
    private volatile String routingCategory;
    /** 智能路由要求的坐席技能。 */
    private volatile String requiredSkill;
    /** 智能路由给出的优先级原文。 */
    private volatile String routingPriority;
    /** 智能路由识别的用户情绪。 */
    private volatile String emotion;
    /** 推荐坐席列表 JSON（仅供 HITL 展示，不自动派单）。 */
    private volatile String suggestedAssignees;
    /** 被重新打开的次数。 */
    private volatile int reopenCount;

    private volatile long updatedAtMs;
    private volatile long handoffAtMs;
    private volatile long claimedAtMs;
    private volatile long resolvedAtMs;
    private volatile long closedAtMs;
    /** 用户最后活跃时间戳（毫秒）：用户发消息 / 主动操作时刷新，供空闲超时巡检判定。 */
    private volatile long lastUserActiveAtMs;

    private Ticket(String id, String sessionId, String userId, long createdAtMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.createdAtMs = createdAtMs;
    }

    /** 建单静态工厂：初始 AI 自助服务中（AI_SERVING），默认普通优先级。 */
    public static Ticket create(String id, String sessionId, String userId, String title, TicketCategory category) {
        Ticket ticket = new Ticket(id, sessionId, userId, System.currentTimeMillis());
        ticket.title = title;
        ticket.category = category == null ? TicketCategory.OTHER : category;
        ticket.priority = TicketPriority.NORMAL;
        ticket.status = TicketStatus.AI_SERVING;
        ticket.updatedAtMs = ticket.createdAtMs;
        // 建单即视为一次用户活跃，避免刚建单就被空闲巡检误判
        ticket.lastUserActiveAtMs = ticket.createdAtMs;
        return ticket;
    }

    /**
     * 请求转人工：AI_SERVING → WAITING_AGENT（记录转人工原因与排队时间戳）。
     *
     * <p>幂等设计：若已处于 WAITING_AGENT / PROCESSING / ON_HOLD（已在人工链路），返回
     * {@code false} 表示未发生流转（不重复登记、不重复发事件）；其余态（待确认 / 已解决 / 已关闭）
     * 请求转人工无意义，fast-fail。</p>
     *
     * @return true 表示发生了 AI_SERVING → WAITING_AGENT 流转；false 表示已在人工链路的幂等空转
     */
    public boolean requestHandoff(String reason) {
        if (status == TicketStatus.AI_SERVING) {
            this.status = TicketStatus.WAITING_AGENT;
            this.handoffReason = reason;
            this.handoffAtMs = System.currentTimeMillis();
            markUserActive();
            touch();
            return true;
        }
        if (status == TicketStatus.WAITING_AGENT || status == TicketStatus.PROCESSING
            || status == TicketStatus.ON_HOLD) {
            return false;
        }
        throw new IllegalStateException(illegal("requestHandoff"));
    }

    /** 撤销转人工：WAITING_AGENT → AI_SERVING（用户改主意继续自助）。 */
    public void cancelHandoff() {
        requireStatus("cancelHandoff", TicketStatus.WAITING_AGENT);
        this.status = TicketStatus.AI_SERVING;
        touch();
    }

    /** 坐席接单：WAITING_AGENT → PROCESSING（绑定坐席、记接单时间）。 */
    public void claim(String agentId) {
        requireStatus("claim", TicketStatus.WAITING_AGENT);
        this.status = TicketStatus.PROCESSING;
        this.assignee = agentId;
        this.claimedAtMs = System.currentTimeMillis();
        touch();
    }

    /** 挂起：PROCESSING → ON_HOLD（等待用户补料 / 第三方回复）。 */
    public void hold() {
        requireStatus("hold", TicketStatus.PROCESSING);
        this.status = TicketStatus.ON_HOLD;
        touch();
    }

    /** 恢复处理：ON_HOLD → PROCESSING。 */
    public void resume() {
        requireStatus("resume", TicketStatus.ON_HOLD);
        this.status = TicketStatus.PROCESSING;
        touch();
    }

    /** 转回队列：PROCESSING | ON_HOLD → WAITING_AGENT（清空坐席、刷新排队时间戳，重新等接单）。 */
    public void transferToPool() {
        if (status != TicketStatus.PROCESSING && status != TicketStatus.ON_HOLD) {
            throw new IllegalStateException(illegal("transferToPool"));
        }
        this.status = TicketStatus.WAITING_AGENT;
        this.assignee = null;
        this.handoffAtMs = System.currentTimeMillis();
        touch();
    }

    /** 转派其他坐席：仅 PROCESSING 态换绑坐席，状态不变。 */
    public void transferToAgent(String agentId) {
        requireStatus("transferToAgent", TicketStatus.PROCESSING);
        this.assignee = agentId;
        touch();
    }

    /** 标记处理完毕：PROCESSING → WAITING_CONFIRM（记处理结论，待用户确认）。 */
    public void markResolved(String note) {
        requireStatus("markResolved", TicketStatus.PROCESSING);
        this.status = TicketStatus.WAITING_CONFIRM;
        this.resolveNote = note;
        touch();
    }

    /** 用户确认：WAITING_CONFIRM → RESOLVED（记解决时间戳）。 */
    public void confirm() {
        requireStatus("confirm", TicketStatus.WAITING_CONFIRM);
        this.status = TicketStatus.RESOLVED;
        this.resolvedAtMs = System.currentTimeMillis();
        markUserActive();
        touch();
    }

    /**
     * 坐席在兼容的人机切换端点直接结案：PROCESSING | ON_HOLD → RESOLVED。
     *
     * <p>完整工单工作台仍使用 markResolved → 用户 confirm 两步语义；旧的 /handoffs/{id}/resolve
     * 合同自始就是“坐席结案并回收给 AI”，因此由权威状态机提供单步动作，避免应用层用两次事务
     * 拼接状态、在中途失败时留下 WAITING_CONFIRM 半状态。</p>
     */
    public void resolveHandoff(String note) {
        if (status != TicketStatus.PROCESSING && status != TicketStatus.ON_HOLD) {
            throw new IllegalStateException(illegal("resolveHandoff"));
        }
        this.status = TicketStatus.RESOLVED;
        this.resolveNote = note;
        this.resolvedAtMs = System.currentTimeMillis();
        touch();
    }

    /** 用户驳回：WAITING_CONFIRM → PROCESSING（问题未解决，打回坐席继续处理）。 */
    public void reject(String reason) {
        requireStatus("reject", TicketStatus.WAITING_CONFIRM);
        this.status = TicketStatus.PROCESSING;
        this.resolveNote = reason;
        markUserActive();
        touch();
    }

    /** 关闭工单：AI_SERVING | RESOLVED | WAITING_CONFIRM → CLOSED（记关闭时间戳）。 */
    public void close(String reason) {
        if (status != TicketStatus.AI_SERVING && status != TicketStatus.RESOLVED
            && status != TicketStatus.WAITING_CONFIRM) {
            throw new IllegalStateException(illegal("close"));
        }
        this.status = TicketStatus.CLOSED;
        this.resolveNote = reason;
        this.closedAtMs = System.currentTimeMillis();
        touch();
    }

    /** 智能路由结果回写：不改变工单状态，仅更新同一权威工单上的分配建议。 */
    public void applyRoutingSuggestion(String routingCategory, String requiredSkill,
                                       String routingPriority, String emotion,
                                       String suggestedAssignees) {
        requireNotClosed("applyRoutingSuggestion");
        this.routingCategory = routingCategory;
        this.requiredSkill = requiredSkill;
        this.routingPriority = routingPriority;
        this.emotion = emotion;
        this.suggestedAssignees = suggestedAssignees;
        touch();
    }

    /**
     * 强制关闭：允许从任意非 CLOSED 态直接结案（记关闭时间戳与原因）。
     *
     * <p>与 {@link #close(String)} 的差异：close 只放行 AI_SERVING/RESOLVED/WAITING_CONFIRM 三个
     * 前置态（下游用户主动关单依赖此约束）；forceClose 覆盖 WAITING_AGENT/PROCESSING/ON_HOLD 等
     * 人工链路中的态，专供空闲超时自动结束与用户强制结束使用。已 CLOSED 再强关 fast-fail（幂等无意义）。</p>
     */
    public void forceClose(String reason) {
        if (status == TicketStatus.CLOSED) {
            throw new IllegalStateException(illegal("forceClose"));
        }
        this.status = TicketStatus.CLOSED;
        this.resolveNote = reason;
        this.closedAtMs = System.currentTimeMillis();
        touch();
    }

    /** 重新打开：RESOLVED | CLOSED → WAITING_AGENT（reopen 计数 +1，清坐席、刷新排队时间戳）。 */
    public void reopen(String reason) {
        if (status != TicketStatus.RESOLVED && status != TicketStatus.CLOSED) {
            throw new IllegalStateException(illegal("reopen"));
        }
        this.status = TicketStatus.WAITING_AGENT;
        this.assignee = null;
        this.handoffReason = reason;
        this.reopenCount++;
        this.handoffAtMs = System.currentTimeMillis();
        markUserActive();
        touch();
    }

    /**
     * 重新打开回 AI 自助：RESOLVED | CLOSED → AI_SERVING（reopen 计数 +1，清坐席）。
     *
     * <p>用户端"重新开始对话"专用：重开后由 AI 继续服务，需要人工时用户再显式转人工；
     * 区别于 {@link #reopen}（重开直达人工排队，供"问题未解决重新找人工"场景）。</p>
     */
    public void reopenToAi(String reason) {
        if (status != TicketStatus.RESOLVED && status != TicketStatus.CLOSED) {
            throw new IllegalStateException(illegal("reopenToAi"));
        }
        this.status = TicketStatus.AI_SERVING;
        this.assignee = null;
        this.handoffReason = reason;
        this.reopenCount++;
        markUserActive();
        touch();
    }

    /**
     * 刷新用户最后活跃时间：用户发消息 / 主动操作（转人工/确认/驳回/重开）时调用。
     *
     * <p>非状态流转（不改 {@code status}），仅重置空闲计时基准，避免活跃会话被空闲巡检误关。
     * 各用户驱动的流转方法内部已联动调用；独立刷新（如用户发消息）由 {@code TicketService} 编排。</p>
     */
    public void markUserActive() {
        this.lastUserActiveAtMs = System.currentTimeMillis();
    }

    /** 变更优先级：任意非 CLOSED 态可改。 */
    public void changePriority(TicketPriority newPriority) {
        requireNotClosed("changePriority");
        this.priority = newPriority;
        touch();
    }

    /** 变更分类：任意非 CLOSED 态可改。 */
    public void changeCategory(TicketCategory newCategory) {
        requireNotClosed("changeCategory");
        this.category = newCategory;
        touch();
    }

    /**
     * 标题回填：仅当当前标题为空白时用给定标题补白（不限状态，非业务流转，只是补齐展示用标题）。
     *
     * <p>典型用于会话首条用户消息到达后，用消息前若干字回填此前建单时留空的标题。已有标题则不覆盖
     * （返回 false）；给定标题为空白也不回填（无意义，返回 false）。发生回填时刷新更新时间戳。</p>
     *
     * @return true 表示发生了回填；false 表示已有标题或给定标题为空白
     */
    public boolean fillTitleIfBlank(String title) {
        if (this.title != null && !this.title.isBlank()) {
            return false;
        }
        if (title == null || title.isBlank()) {
            return false;
        }
        this.title = title;
        touch();
        return true;
    }

    /** 刷新更新时间戳（每次成功流转调用）。 */
    private void touch() {
        this.updatedAtMs = System.currentTimeMillis();
    }

    private void requireStatus(String action, TicketStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(illegal(action));
        }
    }

    private void requireNotClosed(String action) {
        if (status == TicketStatus.CLOSED) {
            throw new IllegalStateException(illegal(action));
        }
    }

    private String illegal(String action) {
        return "illegal ticket transition: action=" + action + ", id=" + id + ", status=" + status;
    }

    /**
     * 供持久化层从数据源重建工单——跳过状态机校验（不是新的业务动作，只是把已发生的流转结果读回内存）。
     * 包级可见，仅供本包内 {@link TicketStore} 实现使用。
     */
    static Ticket reconstruct(String id, String sessionId, String userId, String title,
                              TicketCategory category, TicketPriority priority, TicketStatus status,
                              String assignee, String handoffReason, String resolveNote, int reopenCount,
                              long createdAtMs, long updatedAtMs, long handoffAtMs, long claimedAtMs,
                              long resolvedAtMs, long closedAtMs, long lastUserActiveAtMs) {
        return reconstruct(id, sessionId, userId, title, category, priority, status, assignee,
            handoffReason, resolveNote, reopenCount, createdAtMs, updatedAtMs, handoffAtMs,
            claimedAtMs, resolvedAtMs, closedAtMs, lastUserActiveAtMs,
            null, null, null, null, null);
    }

    static Ticket reconstruct(String id, String sessionId, String userId, String title,
                              TicketCategory category, TicketPriority priority, TicketStatus status,
                              String assignee, String handoffReason, String resolveNote, int reopenCount,
                              long createdAtMs, long updatedAtMs, long handoffAtMs, long claimedAtMs,
                              long resolvedAtMs, long closedAtMs, long lastUserActiveAtMs,
                              String routingCategory, String requiredSkill, String routingPriority,
                              String emotion, String suggestedAssignees) {
        Ticket ticket = new Ticket(id, sessionId, userId, createdAtMs);
        ticket.title = title;
        ticket.category = category;
        ticket.priority = priority;
        ticket.status = status;
        ticket.assignee = assignee;
        ticket.handoffReason = handoffReason;
        ticket.resolveNote = resolveNote;
        ticket.reopenCount = reopenCount;
        ticket.updatedAtMs = updatedAtMs;
        ticket.handoffAtMs = handoffAtMs;
        ticket.claimedAtMs = claimedAtMs;
        ticket.resolvedAtMs = resolvedAtMs;
        ticket.closedAtMs = closedAtMs;
        ticket.lastUserActiveAtMs = lastUserActiveAtMs;
        ticket.routingCategory = routingCategory;
        ticket.requiredSkill = requiredSkill;
        ticket.routingPriority = routingPriority;
        ticket.emotion = emotion;
        ticket.suggestedAssignees = suggestedAssignees;
        return ticket;
    }
}
