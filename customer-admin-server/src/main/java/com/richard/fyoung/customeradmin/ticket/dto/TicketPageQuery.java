package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

/**
 * 工单分页查询参数。admin API 用 {@code pageNum/pageSize}，客户端映射为 8080 的 {@code page/size}。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TicketPageQuery {
    private String status;
    private String category;
    private String priority;
    /** 受理坐席（登录名）过滤。 */
    private String assignee;
    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
