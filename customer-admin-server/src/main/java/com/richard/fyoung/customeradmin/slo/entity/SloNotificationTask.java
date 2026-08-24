package com.richard.fyoung.customeradmin.slo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 告警事件的可靠站内通知任务；由数据库租约在多副本间领取。 */
@Data
@TableName("ai_slo_notification_task")
public class SloNotificationTask {

    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private Long eventId;
    private Long alertId;
    private Long policyId;
    private String eventType;
    private String title;
    private String content;
    private String status;
    private Integer attempts;
    private Long nextAttemptAtMs;
    private String leaseOwner;
    private Long leaseUntilMs;
    private String lastError;
    private Integer recipientCount;
    private Long createdAtMs;
    private Long updatedAtMs;
    private Long deliveredAtMs;
}
