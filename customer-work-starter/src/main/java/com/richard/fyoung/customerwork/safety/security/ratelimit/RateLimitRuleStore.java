package com.richard.fyoung.customerwork.safety.security.ratelimit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 限流规则存储 SPI（持久化扩展点，照既有 Store SPI 模式）。
 *
 * <p>默认 {@link InMemoryRateLimitRuleStore}（进程内、空规则，等价于"只用 yml 全局兜底"）；
 * {@code security.rate-limit.store-mode=jdbc} 时落 {@link MybatisRateLimitRuleStore}
 * （{@code cw_rate_limit_rule} 表），供后台运营维护。</p>
 *
 * <p><b>读失败语义与敏感词相反，是 fail-open</b>：{@link #findEnabled()} 返回 {@code Optional.empty()} 时
 * {@link RateLimitRuleProvider} 保留上次快照、从未加载成功则走 yml 全局兜底——限流是保护措施，
 * 读不到规则就把全站请求判 429 是自伤；而敏感词读不到词表必须 fail-closed，因为漏放是合规事故。
 * 两处方向刻意不同，别照着彼此改。</p>
 * @author owlzhangfq@gmail.com
 */
public interface RateLimitRuleStore {

    /** 全部规则（含停用），供后台展示。 */
    List<RateLimitRule> findAll();

    /**
     * 仅启用的规则。{@code Optional.of(list)} 表示读取成功（空 list 是合法的"没配规则"），
     * {@code Optional.empty()} 表示读取失败（DB 不可达等）。
     */
    Optional<List<RateLimitRule>> findEnabled();

    /** 保存（新建或更新）一条规则。 */
    void save(RateLimitRule rule);

    /**
     * 规则版本指纹（供 {@link RateLimitRuleProvider} 判断是否需要换快照）。
     * 语义同 {@code SensitiveWordStore#fingerprint()}：{@code empty} 表示读取失败。
     */
    default Optional<String> fingerprint() {
        return findEnabled().map(rules -> {
            List<String> items = new ArrayList<>(rules.size());
            for (RateLimitRule rule : rules) {
                items.add(rule.pathPrefix() + '|' + rule.dimension() + '|' + rule.limitCount()
                    + '|' + rule.algorithm() + '|' + rule.windowSeconds() + '|' + rule.priority());
            }
            Collections.sort(items);
            return rules.size() + ":" + Integer.toHexString(String.join(";", items).hashCode());
        });
    }
}
