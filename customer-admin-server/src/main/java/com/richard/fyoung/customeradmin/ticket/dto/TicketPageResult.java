package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

import java.util.List;

/**
 * 工单分页结果（对应 8080 的 {@code {"total":n,"items":[..]}}）。
 *
 * <p>直接透出 8080 的分页契约（total/items），不二次包装成 admin-server 其它列表的
 * pageNum/pageSize/list 结构——本模块是纯代理，保持与上游一致，前端按此单独适配。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class TicketPageResult {
    private long total;
    private List<TicketVO> items;
}
