package com.richard.fyoung.customerwork.core.constant;

/**
 * 事实日志（L3 记忆，{@code cw_fact_log.type}）的类型编码。
 *
 * <p>写入方与统计方靠这个字符串对齐：质量兜底由 {@code QualityFeedbackRecorder} 写、
 * 由 {@code BusinessAnalyticsService} 按类型过滤统计。两边此前各写一份字面量，
 * 改一处的表现是<b>看板指标变 0 而链路不报错</b>——最难发现的那类缺陷。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class FactTypes {

    /** 回答质量不达标触发兜底。 */
    public static final String QUALITY_FAILURE = "quality-failure";

    /** 用户负向反馈。 */
    public static final String NEGATIVE_FEEDBACK = "negative-feedback";

    private FactTypes() {
    }
}
