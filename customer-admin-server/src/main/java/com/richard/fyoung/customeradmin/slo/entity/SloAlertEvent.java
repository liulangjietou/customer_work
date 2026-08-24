package com.richard.fyoung.customeradmin.slo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 告警状态迁移的不可变事件事实。 */
@Data
@TableName("ai_slo_alert_event")
public class SloAlertEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long alertId;
    private Long policyId;
    private String eventType;
    private Long actorUserId;
    private BigDecimal shortBurnRate;
    private BigDecimal longBurnRate;
    private LocalDateTime occurredAt;
}
