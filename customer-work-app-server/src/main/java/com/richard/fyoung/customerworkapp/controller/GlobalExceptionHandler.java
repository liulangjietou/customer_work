package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.security.RequestIdWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把校验失败与未预期异常转换为统一的 JSON 错误响应，
 * 避免把堆栈直接暴露给调用方。
 *
 * <p>错误响应体携带 {@code requestId}（来自 {@link RequestIdWebFilter} 写入的 X-Request-Id 响应头），
 * 让用户报障时可直接提供该 ID，运维凭之在日志中定位到本次请求全链路——把"用户报障 → 根因日志"
 * 的路径从时间戳人肉对齐缩短为一次精确检索。</p>
 * @author owlzhangfq@gmail.com
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 请求体参数校验失败（如 message 为空）。 */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(WebExchangeBindException ex,
                                                                 ServerWebExchange exchange) {
        String detail = ex.getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, detail.isBlank() ? "请求参数校验失败" : detail, exchange);
    }

    /**
     * 框架级状态异常（如静态资源 404 / NoResourceFoundException）按原状态码透传，
     * 避免把 Swagger UI、webjars 等正常的 404 误转成 500。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex,
                                                                    ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return build(status, ex.getReason() == null ? status.getReasonPhrase() : ex.getReason(), exchange);
    }

    /** 其它未预期异常。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, ServerWebExchange exchange) {
        // 日志已由 MDC 自动带上 requestId/sessionId（见 MdcContextLifter），此处无需重复拼接
        log.error("unhandled exception, code={}", "UNHANDLED_ERROR", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请稍后再试", exchange);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
                                                      ServerWebExchange exchange) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        String requestId = resolveRequestId(exchange);
        if (StringUtils.hasText(requestId)) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(status).body(body);
    }

    /** 从响应头读取 RequestIdWebFilter 已写入的 X-Request-Id（最高优先级过滤器先于业务执行，故此时必已存在）。 */
    private String resolveRequestId(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }
        return exchange.getResponse().getHeaders().getFirst(RequestIdWebFilter.HEADER);
    }
}
