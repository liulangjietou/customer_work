package com.richard.fyoung.customeradmin.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租户日用量归集：账单与报表的数据源。
 *
 * <p>金额在归集时按当日单价算好落库，而不是查询时实时算——单价会变，
 * 实时算会让历史账单随调价而变动，对不上已经出过的账。</p>
 *
 * <p>本表参与租户自动过滤：租户管理员看自己的用量，控制面跨租户查询走
 * {@code CrossTenantOperations}。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_tenant_usage_daily")
public class CwTenantUsageDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private LocalDate statDate;
    private String provider;
    private String modelName;
    private Long callCount;
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedTokens;
    private Long totalTokens;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
