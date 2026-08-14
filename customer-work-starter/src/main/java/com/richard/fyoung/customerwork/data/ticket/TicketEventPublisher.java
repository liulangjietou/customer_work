package com.richard.fyoung.customerwork.data.ticket;

/**
 * 工单事件发布 SPI：内存模式同步通知监听器，JDBC 模式写数据库 Outbox。
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface TicketEventPublisher {

    /** 发布一次已经落审计表的工单事件。 */
    void publish(Ticket ticket, TicketEvent event);
}
