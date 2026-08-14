package com.richard.fyoung.customerwork.data.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** cw_outbox_message 持久化对象。 */
@Data
@TableName("cw_outbox_message")
public class OutboxMessageDO {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String tenantId;
    private String type;
    private String aggregateId;
    private String payload;
    private String status;
    private Integer attempts;
    private Long nextAttemptAtMs;
    private String leaseOwner;
    private Long leaseUntilMs;
    private String lastError;
    private Long createdAtMs;
    private Long finishedAtMs;
}
