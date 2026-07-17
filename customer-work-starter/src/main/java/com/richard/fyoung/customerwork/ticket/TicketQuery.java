package com.richard.fyoung.customerwork.ticket;

/**
 * 工单分页查询条件（各过滤项为 null 表示不限制）。
 *
 * @param status   状态过滤
 * @param assignee 坐席过滤
 * @param category 分类过滤
 * @param priority 优先级过滤
 * @param userId   用户过滤
 * @param pageNum  页码（从 1 起）
 * @param pageSize 每页条数
 * @author owlzhangfq@gmail.com
 */
public record TicketQuery(TicketStatus status, String assignee, TicketCategory category,
                          TicketPriority priority, String userId, int pageNum, int pageSize) {

    /** 归一化页码 / 页大小：页码最小 1，页大小落在 [1, 200]。 */
    public int normalizedPageNum() {
        return Math.max(pageNum, 1);
    }

    public int normalizedPageSize() {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 分页偏移量（供 SQL LIMIT offset 使用）。 */
    public int offset() {
        return (normalizedPageNum() - 1) * normalizedPageSize();
    }

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
}
