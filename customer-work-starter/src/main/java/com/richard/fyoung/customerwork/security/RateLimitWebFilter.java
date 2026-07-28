package com.richard.fyoung.customerwork.security;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitAlgorithm;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitDimension;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitRule;
import com.richard.fyoung.customerwork.security.ratelimit.RateLimitRuleProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>采用进程内计数；分布式部署应换成基于 Redis 的分布式限流（如 Bucket4j + Redis），
 * 本实现接口不变、可平滑替换。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitWebFilter implements WebFilter {

    /** 进程内跟踪的客户端窗口数量上限，超过则清扫过期窗口，避免 Map 无界增长导致内存泄漏。 */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    /** 全局兜底层的固定窗口大小（秒）：与规则化之前的"每分钟"语义保持一致。 */
    private static final int DEFAULT_WINDOW_SECONDS = 60;

    private final CustomerWorkProperties properties;
    /** 规则快照提供者；规则功能未装配时为 null，此时只走全局兜底层。 */
    private final RateLimitRuleProvider ruleProvider;
    private final Map<String, Object> windows = new ConcurrentHashMap<>();

    public RateLimitWebFilter(CustomerWorkProperties properties,
                              ObjectProvider<RateLimitRuleProvider> ruleProviderProvider) {
        this.properties = properties;
        this.ruleProvider = ruleProviderProvider == null ? null : ruleProviderProvider.getIfAvailable();
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

    /**
     * 固定时间窗：同一窗口内累计请求数不超过 limit。
     *
     * <p>窗口序号按 {@code now / windowMs} 算，不同规则可以有不同窗口大小；每个窗口自带到期时刻，
     * 清扫时只按到期时刻判断——<b>不能</b>按"是否等于当前窗口序号"判断，那样窗口大小不同的规则
     * 会互相把对方的有效窗口误删。</p>
     */
    boolean allowFixed(String clientId, int limit, int windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        long now = System.currentTimeMillis();
        long currentWindow = now / windowMs;
        FixedWindow window = (FixedWindow) windows.compute(clientId, (k, w) -> {
            if (w == null || ((FixedWindow) w).window != currentWindow) {
                return new FixedWindow(currentWindow, (currentWindow + 1) * windowMs);
            }
            return w;
        });
        // 跟踪的客户端过多时，清扫掉已到期的窗口，防止 Map 无界增长
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            evictExpiredFixedWindows(now);
        }
        return window.count.incrementAndGet() <= limit;
    }

    /**
     * 滑动时间窗：在最近 windowSeconds 秒内的请求数不超过 limit。
     * 用 ArrayDeque 记录每个请求的时间戳，过期出队，当前队列长度即为窗口内请求数。
     */
    boolean allowSliding(String clientId, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        long windowStart = now - windowMs;
        SlidingWindow sw = (SlidingWindow) windows.compute(clientId, (k, existing) -> {
            SlidingWindow w = existing == null ? new SlidingWindow(windowMs) : (SlidingWindow) existing;
            // 清除窗口外的过期时间戳
            synchronized (w.timestamps) {
                Iterator<Long> it = w.timestamps.iterator();
                while (it.hasNext() && it.next() < windowStart) {
                    it.remove();
                }
            }
            return w;
        });
        // 跟踪的客户端过多时，清扫空窗口
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            evictEmptySlidingWindows(now);
        }
        synchronized (sw.timestamps) {
            if (sw.timestamps.size() >= limit) {
                return false;
            }
            sw.timestamps.addLast(now);
            return true;
        }
    }

    /** 移除已到期的固定窗口（按窗口自带的到期时刻，跨规则安全）。 */
    private void evictExpiredFixedWindows(long now) {
        windows.entrySet().removeIf(e ->
            e.getValue() instanceof FixedWindow fw && fw.expireAtMs <= now);
    }

    /** 移除已空或整体过期的滑动窗口（按窗口自带的窗口大小判断，跨规则安全）。 */
    private void evictEmptySlidingWindows(long now) {
        windows.entrySet().removeIf(e -> {
            if (!(e.getValue() instanceof SlidingWindow sw)) {
                return false;
            }
            synchronized (sw.timestamps) {
                return sw.timestamps.isEmpty() || sw.timestamps.peekFirst() < now - sw.windowMs;
            }
        });
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

    /** 固定时间窗内的计数（自带到期时刻，供跨规则安全清扫）。 */
    private static final class FixedWindow {
        private final long window;
        private final long expireAtMs;
        private final AtomicInteger count = new AtomicInteger();

        FixedWindow(long window, long expireAtMs) {
            this.window = window;
            this.expireAtMs = expireAtMs;
        }
    }

    /** 滑动时间窗：记录每个请求的时间戳，过期出队（自带窗口大小，供跨规则安全清扫）。 */
    private static final class SlidingWindow {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        private final long windowMs;

        SlidingWindow(long windowMs) {
            this.windowMs = windowMs;
        }
    }
}
