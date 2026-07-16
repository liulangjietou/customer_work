package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

/**
 * 用户订单详情视图对象（列表字段 + 物流轨迹，透传 8080 契约）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class OrderDetailVO {
    private String orderId;
    private String userId;
    private String username;
    private String productId;
    private String productName;
    private String amount;
    private String status;
    private String receiverAddr;
    /** 物流轨迹（仅详情返回）。 */
    private String logisticsTrace;
    private Long createdAtMs;
}
