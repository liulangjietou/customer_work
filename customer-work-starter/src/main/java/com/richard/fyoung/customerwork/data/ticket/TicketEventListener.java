package com.richard.fyoung.customerwork.data.ticket;

/**
 * 工单事件监听器（观察者扩展点）：每次工单状态流转后被 {@code TicketService} 广播。
 *
 * <p>下游声明自己的 {@link TicketEventListener} Bean（如推送坐席工作台、发通知、写数据飞轮）即被自动织入；
 * 监听器内部异常由 {@code TicketService} 兜住（仅 error 日志），不影响主流转与其他监听器。</p>
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
