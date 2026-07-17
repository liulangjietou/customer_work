package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 投诉工单持久化对象（贫血数据袋，映射 {@code cw_complaint} 表）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_complaint")
public class ComplaintDO {

    /** 投诉工单号（应用赋值，非自增）。 */
    @TableId(value = "complaint_no", type = IdType.INPUT)
    private String complaintNo;

    /** 关联订单号（可空）。 */
    private String orderId;

    /** 投诉内容。 */
    private String content;

    /** 状态：PROCESSING/RESOLVED。 */
    private String status;

    /** 创建时间戳（毫秒）。 */
    private long createdAtMs;
}
