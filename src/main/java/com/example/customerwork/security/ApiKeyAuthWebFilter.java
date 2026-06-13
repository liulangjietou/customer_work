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

import java.util.HashSet;
import java.util.Set;

/**
 * API Key 鉴权过滤器（接入层安全）。
 *
 * <p>校验请求头中的 API Key 是否合法；健康检查与 Actuator 端点放行。默认关闭，
 * 生产开启 {@code customer-work.security.auth.enabled=true} 并配置 {@code api-keys} 后强制鉴权。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthWebFilter implements WebFilter {

    private final CustomerWorkProperties properties;

    public ApiKeyAuthWebFilter(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        CustomerWorkProperties.Security.Auth auth = properties.getSecurity().getAuth();
        if (!auth.isEnabled() || isExempt(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String provided = exchange.getRequest().getHeaders().getFirst(auth.getHeaderName());
        Set<String> validKeys = new HashSet<>(auth.getApiKeys());
        if (provided != null && validKeys.contains(provided)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /** 健康检查与可观测端点免鉴权（便于探针 / 监控抓取）。 */
    private boolean isExempt(String path) {
        return path.startsWith("/actuator") || path.equals("/api/customer/health");
    }
}
