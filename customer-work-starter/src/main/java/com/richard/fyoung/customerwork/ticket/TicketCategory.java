package com.richard.fyoung.customerwork.ticket;

/**
 * 工单业务分类：咨询 / 订单 / 售后 / 投诉 / 其他。用于坐席分流与统计。
 * @author owlzhangfq@gmail.com
 */
public enum TicketCategory {

    /** 咨询。 */
    CONSULT,

    /** 订单相关。 */
    ORDER,

    /** 售后。 */
    AFTER_SALE,

    /** 投诉。 */
    COMPLAINT,

    /** 其他（默认兜底）。 */
    OTHER
}
