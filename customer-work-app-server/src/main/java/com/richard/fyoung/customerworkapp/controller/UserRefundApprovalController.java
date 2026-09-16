package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.safety.security.UserPrincipals;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.dao.UserRefundApprovalDao.RefundApprovalView;
import com.richard.fyoung.customerworkapp.service.UserRefundApprovalQueryService;
import com.richard.fyoung.customerworkapp.service.UserSessionGuard;
import io.swagger.v3.oas.annotations.Operation;
import java.util.NoSuchElementException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 当前用户本人会话的退款审批办理记录，仅返回可公开的状态事实。 */
@RestController
@RequestMapping("/api/customer/user/sessions/{sessionId}/refund-approvals")
public class UserRefundApprovalController {
    private static final int MAX_PAGE_SIZE = 50;
    private final UserSessionGuard sessionGuard;
    private final UserRefundApprovalQueryService queryService;

    public UserRefundApprovalController(UserSessionGuard sessionGuard, UserRefundApprovalQueryService queryService) {
        this.sessionGuard = sessionGuard;
        this.queryService = queryService;
    }

    /** 先验证本人会话及精确标识，阻塞读取在恢复认证租户后执行。 */
    @GetMapping
    @Operation(summary = "本会话的退款审批办理记录", description = "审批和执行状态分别返回，不代表到账结果")
    public Mono<PageResult<RefundApprovalView>> list(@PathVariable String sessionId,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
        ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        var user = UserPrincipals.require(exchange);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid pagination");
        }
        return Mono.fromCallable(() -> TenantContext.callWith(user.tenantId(), () -> {
            var ticket = sessionGuard.requireOwned(sessionId, user.userId());
            if (!sessionId.equals(ticket.getSessionId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
            }
            return queryService.findPage(sessionId, user.userId(), page, size);
        })).subscribeOn(Schedulers.boundedElastic()).onErrorMap(UserRefundApprovalController::mapError);
    }

    private static Throwable mapError(Throwable error) {
        if (error instanceof ResponseStatusException) return error;
        if (error instanceof NoSuchElementException) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        if (error instanceof IllegalStateException || error instanceof DataAccessException) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "退款办理记录暂时不可用");
        }
        return error;
    }
}
