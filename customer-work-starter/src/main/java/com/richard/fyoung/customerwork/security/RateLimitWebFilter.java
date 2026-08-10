package com.richard.fyoung.customerwork.security;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.counter.WindowCounter;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitAlgorithm;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitDimension;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitRule;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitRuleProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 限流过滤器（接入层安全，支持固定窗口与滑动窗口算法）。
 *
 * <p><b>两层配置，规则优先</b>：</p>
 * <ol>
 *   <li><b>规则层</b>（{@link RateLimitRuleProvider}，来自 {@code cw_rate_limit_rule} 表，后台可维护）：
 *       按路径前缀匹配，命中即用该规则的维度/阈值/算法/窗口，<b>优先级小的先匹配、首匹配即止</b>；</li>
 *   <li><b>全局兜底层</b>（{@code customer-work.security.rate-limit.*}）：没有任何规则命中时生效，
 *       就是规则化之前的原有行为——所以不配规则时升级前后表现完全一致。</li>
 * </ol>
 *
 * <p>健康检查与 Actuator 端点放行；超限返回 429。算法两选：<b>fixed-window</b> 实现最省但窗口边界
 * 最坏放过 2 倍瞬时流量；<b>sliding-window</b> 无边界突刺，代价是每个计数键要留时间戳队列。</p>
 *
 * <p>计数交给 {@link WindowCounter}：默认进程内，多副本部署把
 * {@code customer-work.distributed.counter-mode} 切成 {@code redis} 即变成跨实例共享配额——
 * 否则 N 个实例各算各的，等于把限额放大成 N 倍。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitWebFilter implements WebFilter {

    /** 全局兜底层的固定窗口大小（秒）：与规则化之前的"每分钟"语义保持一致。 */
    private static final int DEFAULT_WINDOW_SECONDS = 60;

    /** 计数键前缀：与成本熔断共用同一个计数器实现，靠前缀分隔命名空间。 */
    private static final String KEY_PREFIX = "ratelimit:";

    private final CustomerWorkProperties properties;
    /** 规则快照提供者；规则功能未装配时为 null，此时只走全局兜底层。 */
    private final RateLimitRuleProvider ruleProvider;
    private final WindowCounter counter;

    /** 便捷构造：进程内计数。多副本部署走带 {@link WindowCounter} 的构造。 */
    public RateLimitWebFilter(CustomerWorkProperties properties,
                              ObjectProvider<RateLimitRuleProvider> ruleProviderProvider) {
        this(properties, ruleProviderProvider, null);
    }

    @Autowired
    public RateLimitWebFilter(CustomerWorkProperties properties,
                              ObjectProvider<RateLimitRuleProvider> ruleProviderProvider,
                              ObjectProvider<WindowCounter> counterProvider) {
        this.properties = properties;
        this.ruleProvider = ruleProviderProvider == null ? null : ruleProviderProvider.getIfAvailable();
        WindowCounter provided = counterProvider == null ? null : counterProvider.getIfAvailable();
        this.counter = provided == null ? new InMemoryWindowCounter() : provided;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isExempt(path)) {
            return chain.filter(exchange);
        }
        RateLimitRule rule = ruleProvider == null ? null : ruleProvider.match(path);
        boolean allowed = rule == null ? allowByGlobalConfig(exchange) : allowByRule(exchange, rule);
        if (allowed) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return exchange.getResponse().setComplete();
    }

    /** 规则层：命中规则时按规则的维度/阈值/算法/窗口计数。 */
    private boolean allowByRule(ServerWebExchange exchange, RateLimitRule rule) {
        String counterKey = rule.counterKeyPrefix() + ":" + resolveClientId(exchange, rule.dimension());
        int windowSeconds = rule.windowSeconds() <= 0 ? DEFAULT_WINDOW_SECONDS : rule.windowSeconds();
        return rule.algorithm() == RateLimitAlgorithm.SLIDING_WINDOW
            ? allowSliding(counterKey, rule.limitCount(), windowSeconds)
            : allowFixed(counterKey, rule.limitCount(), windowSeconds);
    }

    /** 全局兜底层：无规则命中时的原有行为（关闭即全放行）。 */
    private boolean allowByGlobalConfig(ServerWebExchange exchange) {
        CustomerWorkProperties.Security.RateLimit cfg = properties.getSecurity().getRateLimit();
        if (!cfg.isEnabled()) {
            return true;
        }
        String clientId = resolveClientId(exchange, RateLimitDimension.API_KEY);
        return RateLimitAlgorithm.parse(cfg.getAlgorithm()) == RateLimitAlgorithm.SLIDING_WINDOW
            ? allowSliding(clientId, cfg.getRequestsPerMinute(), cfg.getWindowSeconds())
            : allowFixed(clientId, cfg.getRequestsPerMinute(), DEFAULT_WINDOW_SECONDS);
    }

    /** 固定时间窗（默认每分钟）内累计请求数不超过 limit。 */
    boolean allowFixed(String clientId, int limit) {
        return allowFixed(clientId, limit, DEFAULT_WINDOW_SECONDS);
    }

    /** 固定时间窗：同一窗口内累计请求数不超过 limit。窗口滚动与键回收由计数器实现负责。 */
    boolean allowFixed(String clientId, int limit, int windowSeconds) {
        return counter.increment(KEY_PREFIX + clientId, 1L, windowSeconds) <= limit;
    }

    /** 滑动时间窗：最近 windowSeconds 秒内的请求数不超过 limit。 */
    boolean allowSliding(String clientId, int limit, int windowSeconds) {
        return counter.tryAcquireSliding(KEY_PREFIX + clientId, limit, windowSeconds);
    }

    /** 统一入口：按配置选择算法。 */
    boolean allow(String clientId, int limit) {
        return allowFixed(clientId, limit);
    }

    /**
     * 解析计数维度对应的客户端标识。
     *
     * <p>{@code API_KEY} 维度拿不到 Key 时回退 IP：否则所有匿名请求会共用一个"unknown"配额，
     * 一个刷量方就能把其他匿名用户全挤掉。</p>
     */
    private String resolveClientId(ServerWebExchange exchange, RateLimitDimension dimension) {
        if (dimension == RateLimitDimension.GLOBAL) {
            return "global";
        }
        if (dimension != RateLimitDimension.IP) {
            String apiKey = exchange.getRequest().getHeaders()
                .getFirst(properties.getSecurity().getAuth().getHeaderName());
            if (apiKey != null && !apiKey.isBlank()) {
                return "key:" + apiKey;
            }
        }
        if (exchange.getRequest().getRemoteAddress() != null) {
            return "ip:" + exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private boolean isExempt(String path) {
        return path.startsWith("/actuator") || path.equals("/api/customer/health");
    }
}
