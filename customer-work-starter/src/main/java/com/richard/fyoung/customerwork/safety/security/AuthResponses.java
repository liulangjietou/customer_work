package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 鉴权过滤器的统一 JSON 响应。
 * @author owlzhangfq@gmail.com
 */
final class AuthResponses {

    private AuthResponses() {
    }

    /** 直接写回 401 JSON（不进业务链路），响应体形如 {@code {"status":401,"error":"Unauthorized","message":...}}。 */
    static Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return write(exchange, HttpStatus.UNAUTHORIZED, "Unauthorized", message);
    }

    /** 两种可信凭据声明了不同租户时返回 403，不允许按过滤器顺序覆盖身份。 */
    static Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return write(exchange, HttpStatus.FORBIDDEN, "Forbidden", message);
    }

    /** 租户访问状态拒绝：状态冻结用 403，快照缺失/过期用 503，epoch 不一致用 401。 */
    static Mono<Void> tenantAccessDenied(ServerWebExchange exchange, TenantAccessDecision decision) {
        HttpStatus status = HttpStatus.valueOf(decision.httpStatus());
        return write(exchange, status, status.getReasonPhrase(), decision.message(), decision.code());
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                    String error, String message) {
        return write(exchange, status, error, message, null);
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                    String error, String message, String code) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value() + ",\"error\":\"" + error
            + "\"" + (code == null ? "" : ",\"code\":\"" + code + "\"")
            + ",\"message\":\"" + message + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
