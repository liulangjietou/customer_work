package com.richard.fyoung.customerwork.safety.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** API 安全响应头统一防线：防 MIME 嗅探、点击劫持、敏感引用泄漏与浏览器能力滥用。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersWebFilter implements WebFilter {

    private static final String PERMISSIONS_POLICY =
        "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", PERMISSIONS_POLICY);
        headers.set("Cross-Origin-Resource-Policy", "same-site");
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/api/") || path.startsWith("/actuator")) {
            headers.setCacheControl("no-store");
        }
        if ("https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        return chain.filter(exchange);
    }
}
