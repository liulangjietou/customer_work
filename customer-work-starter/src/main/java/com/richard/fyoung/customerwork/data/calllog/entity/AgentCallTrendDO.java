package com.richard.fyoung.customerwork.data.calllog.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 趋势聚合查询结果承载（一个时间桶），由 Store 转成领域 {@code AgentCallTrendPoint}。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallTrendDO {

    private String bucket;
    private Long cnt;
    private BigDecimal avgDurationMs;
    private Long totalTokens;
}
