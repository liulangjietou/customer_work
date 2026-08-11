package com.richard.fyoung.customerwork.data.ticket;

/**
 * 工单事件（不可变审计记录）：每一次状态流转落一条，构成工单完整操作轨迹。
 *
 * <p>{@code id} 为存储层自增主键（内存实现自增模拟、JDBC 自增列回填），追加时传 0 由存储层赋值。</p>
 *
 * @param id          存储层自增主键（追加前为 0）
 * @param ticketId    所属工单号
 * @param eventType   事件类型
 * @param fromStatus  流转前状态（建单等无前置态时可为 null）
 * @param toStatus    流转后状态
 * @param actorType   动作发起方类型
 * @param actorId     动作发起方标识（可空，如系统动作）
 * @param note        备注（原因 / 结论等，可空）
 * @param createdAtMs 事件时间戳（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record TicketEvent(long id, String ticketId, TicketEventType eventType, TicketStatus fromStatus,
                          TicketStatus toStatus, TicketActorType actorType, String actorId, String note,
                          long createdAtMs) {

    /** 新建事件工厂：id 交由存储层赋值（此处置 0），时间戳取当前。 */
    public static TicketEvent of(String ticketId, TicketEventType eventType, TicketStatus fromStatus,
                                 TicketStatus toStatus, TicketActorType actorType, String actorId, String note) {
        return new TicketEvent(0L, ticketId, eventType, fromStatus, toStatus, actorType, actorId, note,
            System.currentTimeMillis());
    }

    /** 回填自增主键后的副本（存储层落库拿到自增 id 后调用）。 */
    public TicketEvent withId(long assignedId) {
        return new TicketEvent(assignedId, ticketId, eventType, fromStatus, toStatus, actorType, actorId,
            note, createdAtMs);
    }
}
