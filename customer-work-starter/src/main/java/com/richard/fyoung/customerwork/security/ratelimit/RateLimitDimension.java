package com.richard.fyoung.customerwork.security.ratelimit;

/**
 * 限流计数维度：决定"谁跟谁共享这个配额"。
 *
 * <ul>
 *   <li>{@code API_KEY}：按调用方 API Key 各自计数，拿不到 Key 时回退 IP——面向"每个接入方一份配额"；</li>
 *   <li>{@code IP}：按来源 IP 计数——面向"防单机刷量"，同一 Key 的多台机器各算各的；</li>
 *   <li>{@code GLOBAL}：整条路径共享一份配额，不区分调用方——面向"保护下游总容量"（如 LLM 并发预算）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public enum RateLimitDimension {

    API_KEY, IP, GLOBAL;

    /** 宽松解析：空/非法一律回落 {@link #API_KEY}（与旧全局限流的默认行为一致）。 */
    public static RateLimitDimension parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return API_KEY;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return API_KEY;
        }
    }
}
