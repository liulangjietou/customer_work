package com.richard.fyoung.customerwork.safety.security.ratelimit;

/**
 * 限流算法。
 *
 * <ul>
 *   <li>{@code FIXED_WINDOW}：固定窗口计数，实现最省，窗口边界处最坏可放过 2 倍瞬时流量；</li>
 *   <li>{@code SLIDING_WINDOW}：滑动窗口计数，窗口内累计不超阈值，无边界突刺，代价是每个计数键要留时间戳队列。</li>
 * </ul>
 *
 * <p>枚举名与既有 yml 配置值（{@code fixed-window} / {@code sliding-window}）由 {@link #parse} 互通，
 * 保证规则化之后旧配置字面量继续可用。</p>
 * @author owlzhangfq@gmail.com
 */
public enum RateLimitAlgorithm {

    FIXED_WINDOW("fixed-window"),
    SLIDING_WINDOW("sliding-window");

    private final String configValue;

    RateLimitAlgorithm(String configValue) {
        this.configValue = configValue;
    }

    /** yml/DB 中的配置字面量。 */
    public String configValue() {
        return configValue;
    }

    /** 宽松解析：同时接受枚举名与 yml 字面量；空/非法一律回落 {@link #FIXED_WINDOW}。 */
    public static RateLimitAlgorithm parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIXED_WINDOW;
        }
        String normalized = raw.trim();
        for (RateLimitAlgorithm algorithm : values()) {
            if (algorithm.configValue.equalsIgnoreCase(normalized) || algorithm.name().equalsIgnoreCase(normalized)) {
                return algorithm;
            }
        }
        return FIXED_WINDOW;
    }
}
