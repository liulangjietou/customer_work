package com.richard.fyoung.customerwork.safety.security.ratelimit;

/**
 * 一条限流规则（不可变值对象）。
 *
 * <p>语义：请求路径以 {@code pathPrefix} 开头时命中本规则，按 {@code dimension} 分组计数，
 * 在 {@code windowSeconds} 秒窗口内最多放行 {@code limitCount} 次。多条规则同时匹配时，
 * {@code priority} 小的先生效（<b>首匹配即止</b>，不叠加）——叠加语义会让运营很难解释"到底哪条把我限了"。</p>
 *
 * @param id           规则 ID（JDBC 实现回填；也是计数键的前缀，保证不同规则的计数互不串味）
 * @param name         规则名（运营可读，日志与后台展示用）
 * @param pathPrefix   路径前缀，如 {@code /api/customer/chat}
 * @param dimension    计数维度
 * @param limitCount   窗口内允许的最大请求数
 * @param algorithm    限流算法
 * @param windowSeconds 时间窗（秒）
 * @param priority     优先级，越小越先匹配
 * @param enabled      是否启用
 * @author owlzhangfq@gmail.com
 */
public record RateLimitRule(Long id,
                            String name,
                            String pathPrefix,
                            RateLimitDimension dimension,
                            int limitCount,
                            RateLimitAlgorithm algorithm,
                            int windowSeconds,
                            int priority,
                            boolean enabled) {

    /** 计数键前缀：用规则 ID（无 ID 时退回规则名），保证同一客户端在不同规则下的计数彼此独立。 */
    public String counterKeyPrefix() {
        return "rule:" + (id == null ? name : String.valueOf(id));
    }

    /** 路径是否命中本规则（前缀匹配；空前缀视为匹配全部路径）。 */
    public boolean matches(String path) {
        if (pathPrefix == null || pathPrefix.isEmpty()) {
            return true;
        }
        return path != null && path.startsWith(pathPrefix);
    }
}
