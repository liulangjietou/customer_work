package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.common.PageResult;
import com.richard.fyoung.customerwork.ticket.Ticket;
import com.richard.fyoung.customerwork.ticket.TicketActorType;
import com.richard.fyoung.customerwork.ticket.TicketCategory;
import com.richard.fyoung.customerwork.ticket.TicketQuery;
import com.richard.fyoung.customerwork.ticket.TicketService;
import com.richard.fyoung.customerwork.ticket.TicketStatus;
import com.richard.fyoung.customerwork.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 用户侧工单端点（{@code /api/customer/user}，JWT 鉴权）。
 *
 * <p>当前用户主体由 {@code UserAuthWebFilter} 放入 exchange 属性，本控制器不信任任何客户端自报的 userId；
 * <b>所有按工单 id 的操作先校验工单归属</b>（userId 不一致返回 403），会话消息按 {@code u<userId>:} 前缀
 * 校验归属。非法状态流转（IllegalStateException）本地翻译为 409。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/user")
@Tag(name = "用户工单", description = "建会话 / 工单查询 / 消息回放 / 转人工与确认流转")
public class UserTicketController {

    private static final String SESSION_PREFIX = "u";
    private static final String SESSION_DELIMITER = ":";
    private static final int DEFAULT_MESSAGE_LIMIT = 50;

    private final TicketService ticketService;
    private final ChatLogService chatLogService;

    public UserTicketController(TicketService ticketService, ChatLogService chatLogService) {
        this.ticketService = ticketService;
        this.chatLogService = chatLogService;
    }

    /** 带原因的请求体（转人工 / 驳回 / 重开 / 关闭复用）。 */
    public record ReasonRequest(String reason) {
    }

    @Operation(summary = "新建会话", description = "生成归属当前用户的 sessionId 并建单")
    @PostMapping("/sessions")
    public Mono<Map<String, Object>> createSession(ServerWebExchange exchange) {
        UserPrincipal user = principal(exchange);
        String sessionId = SESSION_PREFIX + user.userId() + SESSION_DELIMITER + "conv-" + UUID.randomUUID();
        return blocking(() -> {
            Ticket ticket = ticketService.createForSession(sessionId, user.userId(), null, TicketCategory.CONSULT);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", sessionId);
            body.put("ticketId", ticket.getId());
            return body;
        });
    }

    @Operation(summary = "我的工单分页", description = "按当前用户过滤，可选 status")
    @GetMapping("/tickets")
    public Mono<Map<String, Object>> listTickets(ServerWebExchange exchange,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        UserPrincipal user = principal(exchange);
        TicketQuery query = new TicketQuery(parseStatus(status), null, null, null, user.userId(), page, size);
        return blocking(() -> {
            PageResult<Ticket> result = ticketService.findPage(query);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("total", result.total());
            body.put("items", result.items());
            return body;
        });
    }

    @Operation(summary = "工单详情", description = "含事件轨迹；非本人工单返回 403")
    @GetMapping("/tickets/{id}")
    public Mono<Map<String, Object>> getTicket(@PathVariable String id, ServerWebExchange exchange) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            Ticket ticket = ownedTicket(id, user);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticket", ticket);
            body.put("events", ticketService.findEvents(id));
            return body;
        });
    }

    @Operation(summary = "会话消息回放", description = "游标翻页（beforeId），仅本人会话")
    @GetMapping("/sessions/{sessionId}/messages")
    public Mono<List<ChatMessage>> messages(@PathVariable String sessionId, ServerWebExchange exchange,
                                            @RequestParam(required = false) Long beforeId,
                                            @RequestParam(defaultValue = "" + DEFAULT_MESSAGE_LIMIT) int limit) {
        UserPrincipal user = principal(exchange);
        if (!sessionId.startsWith(SESSION_PREFIX + user.userId() + SESSION_DELIMITER)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "not your session"));
        }
        return blocking(() -> chatLogService.historyBySession(sessionId, beforeId, limit));
    }

    @Operation(summary = "请求转人工", description = "非法状态返回 409")
    @PostMapping("/tickets/{id}/handoff")
    public Mono<Ticket> handoff(@PathVariable String id, ServerWebExchange exchange,
                                @RequestBody(required = false) ReasonRequest request) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            Ticket ticket = ownedTicket(id, user);
            return ticketService.requestHandoff(ticket.getSessionId(), reasonOf(request),
                TicketActorType.USER, user.userId());
        });
    }

    @Operation(summary = "确认解决", description = "WAITING_CONFIRM → RESOLVED，非法状态 409")
    @PostMapping("/tickets/{id}/confirm")
    public Mono<Ticket> confirm(@PathVariable String id, ServerWebExchange exchange) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            ownedTicket(id, user);
            return ticketService.confirm(id, TicketActorType.USER, user.userId());
        });
    }

    @Operation(summary = "驳回", description = "WAITING_CONFIRM → PROCESSING，非法状态 409")
    @PostMapping("/tickets/{id}/reject")
    public Mono<Ticket> reject(@PathVariable String id, ServerWebExchange exchange,
                               @RequestBody(required = false) ReasonRequest request) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            ownedTicket(id, user);
            return ticketService.reject(id, reasonOf(request), TicketActorType.USER, user.userId());
        });
    }

    @Operation(summary = "重新打开", description = "RESOLVED|CLOSED → WAITING_AGENT，非法状态 409")
    @PostMapping("/tickets/{id}/reopen")
    public Mono<Ticket> reopen(@PathVariable String id, ServerWebExchange exchange,
                               @RequestBody(required = false) ReasonRequest request) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            ownedTicket(id, user);
            return ticketService.reopen(id, reasonOf(request), TicketActorType.USER, user.userId());
        });
    }

    @Operation(summary = "关闭工单", description = "AI_SERVING|RESOLVED|WAITING_CONFIRM → CLOSED，非法状态 409")
    @PostMapping("/tickets/{id}/close")
    public Mono<Ticket> close(@PathVariable String id, ServerWebExchange exchange,
                              @RequestBody(required = false) ReasonRequest request) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            ownedTicket(id, user);
            return ticketService.close(id, reasonOf(request), TicketActorType.USER, user.userId());
        });
    }

    // ---- 内部 ----

    /** 从 exchange 属性取当前用户主体（过滤器已保证存在，此处为单一防御点）。 */
    private UserPrincipal principal(ServerWebExchange exchange) {
        UserPrincipal user = exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return user;
    }

    /** 加载工单并校验归属：不存在 404、非本人 403。 */
    private Ticket ownedTicket(String id, UserPrincipal user) {
        Ticket ticket = ticketService.find(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket not found: " + id));
        if (!user.userId().equals(ticket.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your ticket");
        }
        return ticket;
    }

    private String reasonOf(ReasonRequest request) {
        return request == null ? null : request.reason();
    }

    private TicketStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
        }
    }

    /** 阻塞业务在 boundedElastic 执行并统一翻译领域异常。 */
    private <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(this::translate);
    }

    /** 领域异常 → HTTP 状态：已是 ResponseStatusException 原样透传；不存在 404；状态机冲突 409。 */
    private Throwable translate(Throwable e) {
        if (e instanceof ResponseStatusException) {
            return e;
        }
        if (e instanceof NoSuchElementException) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (e instanceof IllegalStateException) {
            return new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return e;
    }
}
