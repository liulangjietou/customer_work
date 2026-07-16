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
import org.springframework.http.ResponseEntity;
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
import java.util.Optional;
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

    /** 用户级已存在进行中会话时的错误码（前端据此提示"存在进行中的会话"）。createSession/reopen 共用同一错误码，仅提示文案按场景区分。 */
    private static final String ACTIVE_SESSION_EXISTS_CODE = "TICKET-ACTIVE-SESSION-EXISTS";
    private static final String CREATE_ACTIVE_SESSION_EXISTS_MSG = "存在进行中的会话，请先结束当前会话再新建";
    private static final String REOPEN_ACTIVE_SESSION_EXISTS_MSG = "存在进行中的会话，请先结束后再重开";
    /** 用户强制结束会话的默认关闭原因（与空闲超时的 idle timeout 区分）。 */
    private static final String USER_FORCE_CLOSE_REASON = "user force close";

    private final TicketService ticketService;
    private final ChatLogService chatLogService;

    public UserTicketController(TicketService ticketService, ChatLogService chatLogService) {
        this.ticketService = ticketService;
        this.chatLogService = chatLogService;
    }

    /** 带原因的请求体（转人工 / 驳回 / 重开复用）。 */
    public record ReasonRequest(String reason) {
    }

    /** 关闭工单请求体：{@code force=true} 走强制结束（任意非 CLOSED 态直达 CLOSED），否则走普通关闭语义。 */
    public record CloseRequest(String reason, Boolean force) {
    }

    @Operation(summary = "新建会话",
        description = "用户级唯一活跃会话：已有进行中会话返回 409（体带 sessionId/ticketId/status），否则建单")
    @PostMapping("/sessions")
    public Mono<ResponseEntity<Map<String, Object>>> createSession(ServerWebExchange exchange) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            // 用户级唯一活跃会话：命中进行中工单则拒绝新建，回传现有会话信息供前端跳转
            Optional<Ticket> active = ticketService.findActiveByUser(user.userId());
            if (active.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(activeSessionBody(active.get(), CREATE_ACTIVE_SESSION_EXISTS_MSG));
            }
            String sessionId = SESSION_PREFIX + user.userId() + SESSION_DELIMITER + "conv-" + UUID.randomUUID();
            Ticket ticket = ticketService.createForSession(sessionId, user.userId(), null, TicketCategory.CONSULT);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", sessionId);
            body.put("ticketId", ticket.getId());
            return ResponseEntity.ok(body);
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

    @Operation(summary = "重新打开",
        description = "RESOLVED|CLOSED → AI_SERVING（重开回 AI 自助，需要人工时用户再显式转人工）；"
            + "用户已存在另一张进行中会话时返回 409（体带该会话 sessionId/ticketId/status），其余非法状态流转 409")
    @PostMapping("/tickets/{id}/reopen")
    public Mono<ResponseEntity<Object>> reopen(@PathVariable String id, ServerWebExchange exchange,
                               @RequestBody(required = false) ReasonRequest request) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            Ticket ticket = ownedTicket(id, user);
            // 用户级唯一活跃会话不变式：reopen 本质是"新开一张活跃工单"，需与 createSession 同样的前置校验，
            // 否则用户已有活跃工单 B 时仍可 reopen 历史工单 A，造成一个用户两张活跃工单
            Optional<Ticket> active = ticketService.findActiveByUser(user.userId());
            if (active.isPresent() && !active.get().getId().equals(ticket.getId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .<Object>body(activeSessionBody(active.get(), REOPEN_ACTIVE_SESSION_EXISTS_MSG));
            }
            // 用户端"重新开始对话"回到 AI 自助而非人工排队（reopen 直达人工的语义保留给坐席/管理链路）
            Ticket reopened = ticketService.reopenToAi(id, reasonOf(request), TicketActorType.USER, user.userId());
            return ResponseEntity.<Object>ok(reopened);
        });
    }

    @Operation(summary = "关闭工单",
        description = "默认 AI_SERVING|RESOLVED|WAITING_CONFIRM → CLOSED（其余态返回 409，前端据此弹强制结束确认框）；"
            + "请求体 force=true 时强制结束（任意非 CLOSED 态直达 CLOSED）")
    @PostMapping("/tickets/{id}/close")
    public Mono<Ticket> close(@PathVariable String id, ServerWebExchange exchange,
                              @RequestBody(required = false) CloseRequest request) {
        UserPrincipal user = principal(exchange);
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        String reason = request == null ? null : request.reason();
        return blocking(() -> {
            ownedTicket(id, user);
            if (force) {
                String closeReason = StringUtils.hasText(reason) ? reason : USER_FORCE_CLOSE_REASON;
                return ticketService.forceClose(id, closeReason, TicketActorType.USER, user.userId());
            }
            return ticketService.close(id, reason, TicketActorType.USER, user.userId());
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

    /** 409 响应体：回传现有进行中会话信息，供前端提示并跳转到该会话；message 按场景（新建/重开）区分文案。 */
    private Map<String, Object> activeSessionBody(Ticket active, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ACTIVE_SESSION_EXISTS_CODE);
        body.put("message", message);
        body.put("sessionId", active.getSessionId());
        body.put("ticketId", active.getId());
        body.put("status", active.getStatus().name());
        return body;
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
