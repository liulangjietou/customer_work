package com.richard.fyoung.customeradmin.slo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLO 告警周期；同一策略同时只允许一个 OPEN/ACKED 告警。 */
@Data
@TableName("ai_slo_alert")
public class SloAlert {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long policyId;
    private Long windowEndMinute;
    private String alertType;
    /** 非恢复告警等于 policyId，恢复后置空，以数据库唯一键保证单一活跃周期。 */
    private Long activePolicyId;
    private String status;
    private BigDecimal shortBurnRate;
    private BigDecimal longBurnRate;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Long ackBy;
    private LocalDateTime ackAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime updateTime;
}
