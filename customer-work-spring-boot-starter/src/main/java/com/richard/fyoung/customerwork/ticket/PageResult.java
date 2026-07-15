package com.richard.fyoung.customerwork.ticket;

import java.util.List;

/**
 * 分页查询结果：总数 + 当前页数据（按 updatedAtMs 倒序）。
 *
 * @param total 满足条件的总条数
 * @param list  当前页工单列表
 * @author owlzhangfq@gmail.com
 */
public record PageResult(long total, List<Ticket> list) {
}
