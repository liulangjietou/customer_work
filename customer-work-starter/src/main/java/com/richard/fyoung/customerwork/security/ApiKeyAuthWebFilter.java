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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

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
        if (matches(provided, auth.getApiKeys())) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /**
     * 常量时间校验：用 {@link MessageDigest#isEqual} 比对，且遍历所有配置的 Key 不做短路返回，
     * 避免按"命中位置 / 前缀匹配长度"产生可被测量的耗时差异（时序侧信道）。
     * 同时省去原先每请求 {@code new HashSet<>(...)} 的临时对象分配。
     */
    private boolean matches(String provided, List<String> validKeys) {
        if (provided == null || validKeys == null || validKeys.isEmpty()) {
            return false;
        }
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String key : validKeys) {
            if (key != null
                && MessageDigest.isEqual(providedBytes, key.getBytes(StandardCharsets.UTF_8))) {
                matched = true;   // 不 break：保持比对次数与输入无关
            }
        }
        return matched;
    }

    /** 健康检查、可观测端点、Swagger 文档免鉴权（便于探针 / 监控抓取 / 查看 API 文档）。 */
    private boolean isExempt(String path) {
        return path.startsWith("/actuator")
            || path.equals("/api/customer/health")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/v3/api-docs")
            || path.startsWith("/webjars");
    }
}
