package com.richard.fyoung.customeradmin.slo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLO 超阈值事实；业务唯一键保证重复评估不会重复告警。 */
@Data
@TableName("ai_slo_alert")
public class SloAlert {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long policyId;
    private Long windowEndMinute;
    private String alertType;
    private BigDecimal shortBurnRate;
    private BigDecimal longBurnRate;
    private LocalDateTime firstSeenAt;
}
