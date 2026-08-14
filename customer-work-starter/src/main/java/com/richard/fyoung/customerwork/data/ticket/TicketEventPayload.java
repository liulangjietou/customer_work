package com.richard.fyoung.customerwork.data.ticket;

/**
 * 工单事件 Outbox 自包含载荷：保存事件发生当刻的工单快照，避免异步投递时读到后续状态。
 */
public record TicketEventPayload(
    String id, String sessionId, String userId, String title,
    TicketCategory category, TicketPriority priority, TicketStatus status,
    String assignee, String handoffReason, String resolveNote, int reopenCount,
    long createdAtMs, long updatedAtMs, long handoffAtMs, long claimedAtMs,
    long resolvedAtMs, long closedAtMs, long lastUserActiveAtMs,
    TicketEvent event) {

    public static TicketEventPayload from(Ticket ticket, TicketEvent event) {
        return new TicketEventPayload(ticket.getId(), ticket.getSessionId(), ticket.getUserId(),
            ticket.getTitle(), ticket.getCategory(), ticket.getPriority(), ticket.getStatus(),
            ticket.getAssignee(), ticket.getHandoffReason(), ticket.getResolveNote(), ticket.getReopenCount(),
            ticket.getCreatedAtMs(), ticket.getUpdatedAtMs(), ticket.getHandoffAtMs(), ticket.getClaimedAtMs(),
            ticket.getResolvedAtMs(), ticket.getClosedAtMs(), ticket.getLastUserActiveAtMs(), event);
    }

    public Ticket toTicket() {
        return Ticket.reconstruct(id, sessionId, userId, title, category, priority, status, assignee,
            handoffReason, resolveNote, reopenCount, createdAtMs, updatedAtMs, handoffAtMs, claimedAtMs,
            resolvedAtMs, closedAtMs, lastUserActiveAtMs);
    }
}
