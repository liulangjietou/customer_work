package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

/**
 * 用户订单视图对象（透传 8080 坐席订单 API 契约，字段一一对应）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class OrderVO {
    private String orderId;
    private String userId;
    /** 下单用户名（8080 JOIN cw_user 带出，可空）。 */
    private String username;
    private String productId;
    private String productName;
    /** 金额（两位小数字符串，8080 侧已格式化）。 */
    private String amount;
    private String status;
    private String receiverAddr;
    private Long createdAtMs;
}
