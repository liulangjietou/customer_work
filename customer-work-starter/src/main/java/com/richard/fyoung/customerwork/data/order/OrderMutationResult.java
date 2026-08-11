package com.richard.fyoung.customerwork.data.order;

/**
 * 订单写操作（改址 / 取消）的结构化结果：把领域判定与 HTTP 语义解耦，接入层据此映射状态码。
 *
 * <ul>
 *   <li>{@link #OK}：操作成功。</li>
 *   <li>{@link #NOT_FOUND}：订单不存在（→ 404）。</li>
 *   <li>{@link #STATE_CONFLICT}：订单状态不允许该操作（如已发货后取消，→ 409）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public enum OrderMutationResult {
    OK,
    NOT_FOUND,
    STATE_CONFLICT
}
