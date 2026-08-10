package com.richard.fyoung.customerwork.data.order;

/**
 * 坐席订单查询条件（各过滤项为空表示不限制）。
 *
 * @param userId   下单用户 ID（精确）
 * @param orderId  订单号（精确）
 * @param status   订单状态（精确）
 * @param username 用户名（模糊，JOIN cw_user 匹配）
 * @param pageNum  页码（从 1 起）
 * @param pageSize 每页条数
 * @author owlzhangfq@gmail.com
 */
public record OrderDirectoryQuery(String userId, String orderId, String status, String username,
                                  int pageNum, int pageSize) {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    /** 归一化页码：最小 1。 */
    public int normalizedPageNum() {
        return Math.max(pageNum, 1);
    }

    /** 归一化页大小：落在 [1, 200]，非法值回落默认 20。 */
    public int normalizedPageSize() {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
