package com.richard.fyoung.customeradmin.contentguard.dto;

import lombok.Data;

import java.util.List;

/**
 * 命中看板统计：总量 + 三个维度分布 + 时间趋势。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SensitiveWordHitStatsVO {

    /** 筛选条件下的命中总数。 */
    private long total;

    /** 按处置动作分布（BLOCK/MASK/REVIEW）。 */
    private List<ContentGuardCountVO> byAction;

    /** 按命中方向分布（INBOUND/OUTBOUND）。 */
    private List<ContentGuardCountVO> byDirection;

    /** 命中最多的词 Top N。 */
    private List<ContentGuardCountVO> topWords;

    /** 时间趋势（按天或按小时，由查询区间跨度自动决定粒度）。 */
    private List<ContentGuardCountVO> trend;

    /** 趋势粒度：day / hour。 */
    private String trendGranularity;
}
