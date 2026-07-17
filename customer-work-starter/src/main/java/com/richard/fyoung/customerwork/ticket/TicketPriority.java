package com.richard.fyoung.customerwork.ticket;

/**
 * 工单优先级：低 / 普通 / 高 / 紧急。新建默认 {@link #NORMAL}。
 * @author owlzhangfq@gmail.com
 */
public enum TicketPriority {

    /** 低。 */
    LOW,

    /** 普通（默认）。 */
    NORMAL,

    /** 高。 */
    HIGH,

    /** 紧急。 */
    URGENT
}
