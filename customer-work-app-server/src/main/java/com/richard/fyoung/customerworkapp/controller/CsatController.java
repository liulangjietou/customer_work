package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.csat.CsatService;
import com.richard.fyoung.customerwork.capability.csat.CsatSummary;
import com.richard.fyoung.customerwork.capability.csat.CsatSurvey;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerworkapp.service.UserSessionGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 会话级满意度（CSAT）端点。
 *
 * <p>查询与提交面向用户端（会话结束后弹评分卡）；汇总面向运营。
 * CSAT 是客服行业最标准的运营指标，与消息级点赞/点踩衡量的是完全不同的东西——
 * 后者看单句质量，本指标看"这次服务整体解决了没有"。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/csat")
@Tag(name = "满意度调查", description = "会话级 CSAT 邀请、评分与汇总")
public class CsatController {

    /** 汇总默认窗口：最近 7 天。CSAT 是按周看的指标，按天看噪声太大。 */
    private static final long DEFAULT_WINDOW_MS = Duration.ofDays(7).toMillis();

    private final CsatService csatService;
    private final UserSessionGuard sessionGuard;

    public CsatController(CsatService csatService, UserSessionGuard sessionGuard) {
        this.csatService = csatService;
        this.sessionGuard = sessionGuard;
    }

    @Operation(summary = "查询调查状态",
        description = "用户端据此决定要不要弹评分卡：无记录=没被邀请，有记录且 score 为空=待评价")
    @GetMapping("/{sessionId}")
    public Mono<ResponseEntity<CsatSurvey>> status(@PathVariable String sessionId,
                                                   ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                sessionGuard.requireOwned(sessionId, principal(exchange).userId());
                return csatService.find(sessionId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "提交满意度评分",
        description = "score 取值 1-5；未被邀请过的会话也接受提交（用户主动评价是好事，会补建记录）")
    @PostMapping("/{sessionId}")
    public Mono<CsatSurvey> submit(@PathVariable String sessionId,
                                   @RequestParam int score,
                                   @RequestParam(required = false) String comment,
                                   ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                sessionGuard.requireOwned(sessionId, principal(exchange).userId());
                return csatService.submit(sessionId, score, comment);
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "满意度汇总",
        description = "CSAT=满意数(4分及以上)/回收数；必须和回收率一起看——回收率低时评分只代表愿意评价的那一小撮人")
    @GetMapping("/summary")
    public Mono<CsatSummary> summary(@RequestParam(required = false) String scopeId,
                                     @RequestParam(required = false) Long windowStartMs,
                                     @RequestParam(required = false) Long windowEndMs) {
        return Mono.fromCallable(() -> {
                long end = windowEndMs != null ? windowEndMs : System.currentTimeMillis();
                long start = windowStartMs != null ? windowStartMs : end - DEFAULT_WINDOW_MS;
                return csatService.summary(scopeId, start, end);
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private UserPrincipal principal(ServerWebExchange exchange) {
        UserPrincipal principal = exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR);
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return principal;
    }
}
