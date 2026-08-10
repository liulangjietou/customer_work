package com.richard.fyoung.customerwork.data.order;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 坐席订单查询行（JOIN {@code cw_order} + {@code cw_user} 的投影，含用户名）。
 *
 * <p>贫血视图对象：{@code OrderMapper.pageForAgent / detailForAgent} 的 resultType，列名经
 * {@code mapUnderscoreToCamelCase} 映射。列表查询不取 {@code logisticsTrace}（恒为 null），仅详情返回。
 * 金额保留 {@link BigDecimal} 原值，格式化（两位小数字符串）交由接入层的 VO 组装。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class OrderDirectoryRow {

    /** 订单号。 */
    private String orderId;
    /** 下单用户 ID。 */
    private String userId;
    /** 下单用户名（来自 cw_user，LEFT JOIN 不命中时为 null）。 */
    private String username;
    /** 商品 ID。 */
    private String productId;
    /** 商品名称。 */
    private String productName;
    /** 订单金额。 */
    private BigDecimal amount;
    /** 订单状态。 */
    private String status;
    /** 收货地址。 */
    private String receiverAddr;
    /** 物流轨迹（列表查询为 null，仅详情返回）。 */
    private String logisticsTrace;
    /** 下单时间戳（毫秒）。 */
    private Long createdAtMs;
}
