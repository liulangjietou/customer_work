package com.richard.fyoung.customerwork.data.ticket;

/**
 * 工单事件监听器（观察者扩展点）：每次工单状态流转后被事件发布器调用。
 *
 * <p>下游声明自己的 {@link TicketEventListener} Bean（如推送坐席工作台、发通知、写数据飞轮）即被自动织入；
 * JDBC 模式经数据库 Outbox 至少投递一次，监听器应以 {@link TicketEvent#id()} 做幂等去重。</p>
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface TicketEventListener {

    /**
     * 工单发生一次状态流转后回调。
     *
     * @param ticket 流转后的最新工单
     * @param event  本次流转事件
     */
    void onTicketEvent(Ticket ticket, TicketEvent event);
}
