package com.richard.fyoung.customerwork.data.order;

import java.util.List;

/**
 * 订单状态字面量（{@code cw_order.status} 列存的值）。
 *
 * <p>取消状态此前在订单目录服务与订单工具后端各写一份中文字面量：两处必须逐字相同，
 * 差一个字就是"工具说取消成功、列表里还是待发货"，SQL 不会报错。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class OrderStatuses {

    public static final String PENDING_PAYMENT = "待支付";
    public static final String PAID = "已支付";
    public static final String PENDING_SHIPMENT = "待发货";
    public static final String SHIPPED = "已发货";
    public static final String RECEIVED = "已签收";
    public static final String CANCELLED = "已取消";
    public static final String REFUNDED = "已退款";

    /** 允许用户自助取消的状态：已发货之后只能走售后，不能直接取消。 */
    public static final List<String> CANCELLABLE = List.of(PENDING_PAYMENT, PAID, PENDING_SHIPMENT);

    private OrderStatuses() {
    }
}
