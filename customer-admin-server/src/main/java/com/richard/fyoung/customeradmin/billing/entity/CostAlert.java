package com.richard.fyoung.customeradmin.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 金额预算告警事实。同一租户、周期、周期键与类型只保留一条，回补归集不会重复告警。
 */
@Data
@TableName("ai_cost_alert")
public class CostAlert {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String period;
    private String periodKey;
    private String alertType;
    private BigDecimal usedAmount;
    private BigDecimal limitAmount;
    private BigDecimal forecastAmount;
    private String status;
    private LocalDateTime firstSeenAt;
    private Long ackBy;
    private LocalDateTime ackAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
