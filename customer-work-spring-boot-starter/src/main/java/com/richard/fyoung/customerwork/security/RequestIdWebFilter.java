package com.richard.fyoung.customerwork.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求 ID 透传过滤器（可观测：全链路关联）。
 *
 * <p>为每个请求生成（或沿用上游传入的）{@code X-Request-Id}，写回响应头并放入 Reactor 上下文
 * （key=requestId），便于跨服务关联日志与排障。最高优先级，先于鉴权 / 限流执行。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String CONTEXT_KEY = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = StringUtils.hasText(incoming)
            ? incoming
            : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(exchange).contextWrite(ctx -> ctx.put(CONTEXT_KEY, requestId));
    }
}
