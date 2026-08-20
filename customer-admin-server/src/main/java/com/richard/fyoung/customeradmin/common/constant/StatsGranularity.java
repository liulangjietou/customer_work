package com.richard.fyoung.customeradmin.common.constant;

/**
 * 统计趋势的时间粒度（接口出入参取值）。
 *
 * <p>内容风控命中统计与智能体调用统计共用同一套粒度词汇，前端按这个字符串渲染横轴。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class StatsGranularity {

    public static final String HOUR = "hour";
    public static final String DAY = "day";

    private StatsGranularity() {
    }
}
