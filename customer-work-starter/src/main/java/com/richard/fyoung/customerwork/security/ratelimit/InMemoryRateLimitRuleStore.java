package com.richard.fyoung.customerwork.security.ratelimit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 进程内限流规则表（默认实现）。
 *
 * <p><b>刻意不带演示种子</b>（这点与 {@code InMemorySensitiveWordStore} 不同）：默认空规则表意味着
 * "一条规则都不匹配"，过滤器随即回退到 yml 的全局兜底配置——也就是升级前的原有行为。凭空塞几条演示
 * 规则反而会在使用方毫不知情的情况下限住真实流量。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryRateLimitRuleStore implements RateLimitRuleStore {

    private final ConcurrentHashMap<Long, RateLimitRule> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);

    @Override
    public List<RateLimitRule> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public Optional<List<RateLimitRule>> findEnabled() {
        // 进程内实现读取不会失败，恒返回 Optional.of（哪怕空 list）
        return Optional.of(store.values().stream()
            .filter(RateLimitRule::enabled)
            .collect(Collectors.toList()));
    }

    @Override
    public void save(RateLimitRule rule) {
        if (rule == null || rule.pathPrefix() == null) {
            return;
        }
        Long id = rule.id() == null ? idGen.incrementAndGet() : rule.id();
        store.put(id, new RateLimitRule(id, rule.name(), rule.pathPrefix(), rule.dimension(),
            rule.limitCount(), rule.algorithm(), rule.windowSeconds(), rule.priority(), rule.enabled()));
    }
}
