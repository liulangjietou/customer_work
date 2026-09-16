package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.security.UserPrincipals;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.dto.UserMessageSource;
import com.richard.fyoung.customerworkapp.dto.UserMessageSourcePreview;
import com.richard.fyoung.customerworkapp.service.UserMessageSourceService;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** JWT 用户的消息原文出口：固定会话归属，恢复租户后进行阻塞读取，所有结果禁止缓存。 */
@RestController
@RequestMapping("/api/customer/user/sessions/{sessionId}/messages/{messageId}/sources")
public class UserMessageSourceController {
    private static final Logger log = LoggerFactory.getLogger(UserMessageSourceController.class);
    private final UserMessageSourceService sources;

    public UserMessageSourceController(UserMessageSourceService sources) {
        this.sources = sources;
    }

    /** 只返回真实留存的来源目录，旧消息没有留存时返回空数组。 */
    @GetMapping
    public Mono<List<UserMessageSource>> sources(@PathVariable String sessionId, @PathVariable String messageId,
                                                 ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        UserPrincipal user = UserPrincipals.require(exchange);
        return read(user, () -> sources.sources(user, sessionId, messageId));
    }

    /** 每次打开都重新读取当前授权下的历史段落，撤权和删除返回清空的不可用结果。 */
    @GetMapping("/{sourceIndex}")
    public Mono<UserMessageSourcePreview> preview(@PathVariable String sessionId, @PathVariable String messageId,
                                                  @PathVariable int sourceIndex, ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        UserPrincipal user = UserPrincipals.require(exchange);
        return read(user, () -> sources.preview(user, sessionId, messageId, sourceIndex));
    }

    private <T> Mono<T> read(UserPrincipal user, Supplier<T> action) {
        return Mono.fromCallable(() -> TenantContext.callWith(user.tenantId(), action))
            .subscribeOn(Schedulers.boundedElastic())
            // 既有工单 Store 以 IllegalStateException 包装读取失败；本出口没有业务写入或状态冲突。
            .onErrorMap(error -> error instanceof DataAccessException || error instanceof IllegalStateException, error -> {
                log.error("customer message source read failed, errorCode={}", "CUSTOMER-SOURCE-READ-FAIL", error);
                return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "source read unavailable", error);
            });
    }
}
