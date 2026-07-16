package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

/**
 * 用户订单分页查询参数。admin API 用 {@code pageNum/pageSize}，客户端映射为 8080 的 {@code page/size}。
 * @author owlzhangfq@gmail.com
 */
@Data
public class OrderPageQuery {
    /** 下单用户 ID（精确）。 */
    private String userId;
    /** 用户名（模糊，8080 侧 JOIN cw_user 匹配）。 */
    private String username;
    /** 订单号（精确）。 */
    private String orderId;
    /** 订单状态（精确）。 */
    private String status;
    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
