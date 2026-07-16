package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单持久化对象（贫血数据袋，仅承载 {@code cw_order} 表的行映射）。
 *
 * <p>字段驼峰经 {@code mapUnderscoreToCamelCase} 自动映射到下划线列名；金额用 {@link BigDecimal}。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_order")
public class OrderDO {

    /** 订单号（应用赋值，非自增）。 */
    @TableId(value = "order_id", type = IdType.INPUT)
    private String orderId;

    /** 下单用户。 */
    private String userId;

    /** 商品ID。 */
    private String productId;

    /** 商品名称。 */
    private String productName;

    /** 订单金额。 */
    private BigDecimal amount;

    /** 订单状态。 */
    private String status;

    /** 收货地址。 */
    private String receiverAddr;

    /** 物流轨迹。 */
    private String logisticsTrace;

    /** 下单时间戳（毫秒）。 */
    private long createdAtMs;
}
