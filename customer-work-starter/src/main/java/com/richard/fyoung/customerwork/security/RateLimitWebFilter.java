package com.richard.fyoung.customerwork.security;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
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
 * <p>按客户端维度（优先 API Key，否则远端 IP）做每分钟请求数限制，超限返回 429。
 * 健康检查与 Actuator 端点放行。默认关闭，开启后保护后端 LLM 调用不被刷量打爆。</p>
 *
 * <p>算法选择（{@code security.rate-limit.algorithm}）：</p>
 * <ul>
 *   <li><b>fixed-window</b>（默认）：固定窗口计数，实现简单但窗口边界处可能有 2x 突刺；</li>
 *   <li><b>sliding-window</b>：滑动窗口计数，在指定时间窗内累计请求不超过 limit，
 *       避免固定窗口边界突刺，限流更平滑。</li>
 * </ul>
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

    private final CustomerWorkProperties properties;
    private final Map<String, Object> windows = new ConcurrentHashMap<>();

    public RateLimitWebFilter(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        CustomerWorkProperties.Security.RateLimit cfg = properties.getSecurity().getRateLimit();
        String path = exchange.getRequest().getPath().value();
        if (!cfg.isEnabled() || isExempt(path)) {
            return chain.filter(exchange);
        }
        String clientId = resolveClientId(exchange);
        boolean isSliding = "sliding-window".equalsIgnoreCase(cfg.getAlgorithm());
        boolean allowed = isSliding
            ? allowSliding(clientId, cfg.getRequestsPerMinute(), cfg.getWindowSeconds())
            : allowFixed(clientId, cfg.getRequestsPerMinute());
        if (allowed) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return exchange.getResponse().setComplete();
    }

    /** 固定时间窗：同一分钟窗口内累计请求数不超过 limit。 */
    boolean allowFixed(String clientId, int limit) {
        long currentMinute = System.currentTimeMillis() / 60_000L;
        FixedWindow window = (FixedWindow) windows.compute(clientId, (k, w) -> {
            if (w == null || ((FixedWindow) w).minute != currentMinute) {
                return new FixedWindow(currentMinute);
            }
            return w;
        });
        // 跟踪的客户端过多时，清扫掉已不属于当前分钟的过期窗口，防止 Map 无界增长。
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            evictStaleFixedWindows(currentMinute);
        }
        return window.count.incrementAndGet() <= limit;
    }

    /**
     * 滑动时间窗：在最近 windowSeconds 秒内的请求数不超过 limit。
     * 用 ArrayDeque 记录每个请求的时间戳，过期出队，当前队列长度即为窗口内请求数。
     */
    boolean allowSliding(String clientId, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;
        SlidingWindow sw = (SlidingWindow) windows.compute(clientId, (k, existing) -> {
            SlidingWindow w = existing == null ? new SlidingWindow() : (SlidingWindow) existing;
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
            evictEmptySlidingWindows(now, windowSeconds);
        }
        synchronized (sw.timestamps) {
            if (sw.timestamps.size() >= limit) {
                return false;
            }
            sw.timestamps.addLast(now);
            return true;
        }
    }

    /** 移除非当前分钟的过期固定窗口。 */
    private void evictStaleFixedWindows(long currentMinute) {
        windows.entrySet().removeIf(e ->
            e.getValue() instanceof FixedWindow fw && fw.minute != currentMinute);
    }

    /** 移除过期的空滑动窗口。 */
    private void evictEmptySlidingWindows(long now, int windowSeconds) {
        long windowStart = now - windowSeconds * 1000L;
        windows.entrySet().removeIf(e -> {
            if (!(e.getValue() instanceof SlidingWindow sw)) {
                return false;
            }
            synchronized (sw.timestamps) {
                return sw.timestamps.isEmpty()
                    || sw.timestamps.peekFirst() < windowStart;
            }
        });
    }

    /** 统一入口：按配置选择算法。 */
    boolean allow(String clientId, int limit) {
        return allowFixed(clientId, limit);
    }

    private String resolveClientId(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders()
            .getFirst(properties.getSecurity().getAuth().getHeaderName());
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        if (exchange.getRequest().getRemoteAddress() != null) {
            return "ip:" + exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private boolean isExempt(String path) {
        return path.startsWith("/actuator") || path.equals("/api/customer/health");
    }

    /** 固定时间窗（分钟）内的计数。 */
    private static final class FixedWindow {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        FixedWindow(long minute) {
            this.minute = minute;
        }
    }

    /** 滑动时间窗：记录每个请求的时间戳，过期出队。 */
    private static final class SlidingWindow {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
    }
}
