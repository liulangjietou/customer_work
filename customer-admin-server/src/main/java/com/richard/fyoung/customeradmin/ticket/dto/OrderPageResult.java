package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

import java.util.List;

/**
 * 用户订单分页结果（对应 8080 的 {@code {"total":n,"items":[..]}}）。
 *
 * <p>直接透出 8080 的分页契约（total/items），本模块是纯代理，与上游保持一致，前端按此单独适配。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class OrderPageResult {
    private long total;
    private List<OrderVO> items;
}
