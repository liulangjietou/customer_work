package com.richard.fyoung.customerwork.safety.security;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 鉴权过滤器的统一 401 JSON 响应（两处鉴权过滤器共用，避免各写一份）。
 * @author owlzhangfq@gmail.com
 */
final class AuthResponses {

    private AuthResponses() {
    }

    /** 直接写回 401 JSON（不进业务链路），响应体形如 {@code {"status":401,"error":"Unauthorized","message":...}}。 */
    static Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
