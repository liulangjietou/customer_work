package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 发票申请持久化对象（贫血数据袋，映射 {@code cw_invoice_request} 表）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_invoice_request")
public class InvoiceRequestDO {

    /** 发票申请自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联订单号。 */
    private String orderId;

    /** 发票抬头。 */
    private String invoiceTitle;

    /** 状态：PENDING/ISSUED。 */
    private String status;

    /** 创建时间戳（毫秒）。 */
    private long createdAtMs;
}
