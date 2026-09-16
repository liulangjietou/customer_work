package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 售后工单持久化对象（贫血数据袋，退款/退货/换货共表 {@code cw_refund}）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_refund")
public class RefundDO {

    /** 创建申请时经过认证的订单租户；显式写入，不依赖租户插件或数据库默认值。 */
    private String tenantId;

    /** 售后工单号（应用赋值，非自增）。 */
    @TableId(value = "refund_no", type = IdType.INPUT)
    private String refundNo;

    /** 关联订单号。 */
    private String orderId;

    /** 类型：REFUND/RETURN/EXCHANGE。 */
    private String type;

    /** 状态：PENDING/APPROVED/DENIED。 */
    private String status;

    /** 退款金额（退货/换货可空）。 */
    private BigDecimal amount;

    /** 诉求原因。 */
    private String reason;

    /** 换货目标规格。 */
    private String newSpec;

    /** 创建时间戳（毫秒）。 */
    private long createdAtMs;
}
