package com.example.customerwork.security;

import com.example.customerwork.config.CustomerWorkProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流过滤器（接入层安全，固定时间窗算法）。
 *
 * <p>按客户端维度（优先 API Key，否则远端 IP）做每分钟请求数限制，超限返回 429。
 * 健康检查与 Actuator 端点放行。默认关闭，开启后保护后端 LLM 调用不被刷量打爆。</p>
 *
 * <p>采用进程内固定窗计数；分布式部署应换成基于 Redis 的分布式限流（如 Bucket4j + Redis），
 * 本实现接口不变、可平滑替换。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitWebFilter implements WebFilter {

    private final CustomerWorkProperties properties;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

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
        if (allow(clientId, cfg.getRequestsPerMinute())) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return exchange.getResponse().setComplete();
    }

    /** 固定时间窗：同一分钟窗口内累计请求数不超过 limit。 */
    boolean allow(String clientId, int limit) {
        long currentMinute = System.currentTimeMillis() / 60_000L;
        Window window = windows.compute(clientId, (k, w) -> {
            if (w == null || w.minute != currentMinute) {
                return new Window(currentMinute);
            }
            return w;
        });
        return window.count.incrementAndGet() <= limit;
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

    /** 一个时间窗（分钟）内的计数。 */
    private static final class Window {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
