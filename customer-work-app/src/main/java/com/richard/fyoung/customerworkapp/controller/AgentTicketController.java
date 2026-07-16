package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.common.PageResult;
import com.richard.fyoung.customerwork.ticket.Ticket;
import com.richard.fyoung.customerwork.ticket.TicketActorType;
import com.richard.fyoung.customerwork.ticket.TicketCategory;
import com.richard.fyoung.customerwork.ticket.TicketPriority;
import com.richard.fyoung.customerwork.ticket.TicketQuery;
import com.richard.fyoung.customerwork.ticket.TicketService;
import com.richard.fyoung.customerwork.ticket.TicketStatus;
import com.richard.fyoung.customerwork.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.ws.WsFrame;
import com.richard.fyoung.customerwork.ws.WsSessionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import java.util.concurrent.Callable;

/**
 * 坐席侧工单端点（{@code /api/customer/agent}，X-Agent-Token 鉴权）。
 *
 * <p>坐席身份由 {@code AgentAuthWebFilter} 凭令牌解析后放入 exchange 属性。抢单冲突（IllegalStateException）
 * 本地翻译为 409；{@code /reply} 是 WS 不可用时的 HTTP 兜底通道——落库坐席消息并尽力推送用户（离线只落库、
 * 不报错）。工单流转均以 {@code AGENT} 身份记账。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/agent")
@Tag(name = "坐席工单", description = "工单队列 / 抢单 / 回复 / 挂起转派结案 / 优先级分类")
public class AgentTicketController {

    private final TicketService ticketService;
    private final ChatLogService chatLogService;
    private final WsSessionRegistry registry;

    public AgentTicketController(TicketService ticketService, ChatLogService chatLogService,
                                 WsSessionRegistry registry) {
        this.ticketService = ticketService;
        this.chatLogService = chatLogService;
        this.registry = registry;
    }

    /** 坐席回复请求体。 */
    public record ReplyRequest(String content) {
    }

    /** 带原因/备注的请求体（挂起 / 结案 / 关闭复用）。 */
    public record NoteRequest(String reason, String note) {
    }

    /** 转派请求体（toAgent 为空 → 转回队列）。 */
    public record TransferRequest(String toAgent) {
    }

    /** 优先级变更请求体。 */
    public record PriorityRequest(String priority) {
    }

    /** 分类变更请求体。 */
    public record CategoryRequest(String category) {
    }

    @Operation(summary = "工单队列", description = "多维过滤分页，返回 {total, items}")
    @GetMapping("/tickets")
    public Mono<Map<String, Object>> listTickets(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String assignee,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String priority,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        TicketQuery query = new TicketQuery(parseStatus(status), StringUtils.hasText(assignee) ? assignee : null,
            parseCategory(category), parsePriority(priority), null, page, size);
        return blocking(() -> {
            PageResult<Ticket> result = ticketService.findPage(query);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("total", result.total());
            body.put("items", result.items());
            return body;
        });
    }

    @Operation(summary = "工单详情", description = "含事件轨迹")
    @GetMapping("/tickets/{id}")
    public Mono<Map<String, Object>> getTicket(@PathVariable String id) {
        return blocking(() -> {
            Ticket ticket = require(id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticket", ticket);
            body.put("events", ticketService.findEvents(id));
            return body;
        });
    }

    @Operation(summary = "工单消息回放", description = "游标翻页（beforeId）")
    @GetMapping("/tickets/{id}/messages")
    public Mono<List<ChatMessage>> messages(@PathVariable String id,
                                            @RequestParam(required = false) Long beforeId,
                                            @RequestParam(defaultValue = "50") int limit) {
        return blocking(() -> chatLogService.historyByTicket(id, beforeId, limit));
    }

    @Operation(summary = "抢单", description = "已被抢返回 409")
    @PostMapping("/tickets/{id}/claim")
    public Mono<Ticket> claim(@PathVariable String id, ServerWebExchange exchange) {
        String agent = agentId(exchange);
        return blocking(() -> ticketService.claim(id, agent));
    }

    @Operation(summary = "坐席回复(HTTP 兜底)", description = "落库并尽力推送用户；用户离线只落库、不报错")
    @PostMapping("/tickets/{id}/reply")
    public Mono<ChatMessage> reply(@PathVariable String id, ServerWebExchange exchange,
                                   @RequestBody ReplyRequest request) {
        String agent = agentId(exchange);
        return blocking(() -> {
            Ticket ticket = require(id);
            if (!agent.equals(ticket.getAssignee())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not the assignee of this ticket");
            }
            ChatMessage message = chatLogService.append(
                ticket.getSessionId(), id, TicketActorType.AGENT, agent, request.content());
            // chat 帧字段与前端契约一致（messageId/sessionId/ticketId/senderType/senderId/content/ts）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", message.messageId());
            data.put("sessionId", message.sessionId());
            data.put("ticketId", message.ticketId());
            data.put("senderType", message.senderType().name());
            data.put("senderId", message.senderId());
            data.put("content", message.content());
            data.put("ts", message.createdAtMs());
            registry.pushToUser(ticket.getUserId(), WsFrame.chat(data));
            return message;
        });
    }

    @Operation(summary = "挂起", description = "PROCESSING → ON_HOLD，非法状态 409")
    @PostMapping("/tickets/{id}/hold")
    public Mono<Ticket> hold(@PathVariable String id, ServerWebExchange exchange,
                             @RequestBody(required = false) NoteRequest request) {
        String agent = agentId(exchange);
        return blocking(() -> ticketService.hold(id, TicketActorType.AGENT, agent));
    }

    @Operation(summary = "恢复处理", description = "ON_HOLD → PROCESSING，非法状态 409")
    @PostMapping("/tickets/{id}/resume")
    public Mono<Ticket> resume(@PathVariable String id, ServerWebExchange exchange) {
        String agent = agentId(exchange);
        return blocking(() -> ticketService.resume(id, TicketActorType.AGENT, agent));
    }

    @Operation(summary = "转派", description = "toAgent 为空转回队列，否则转派指定坐席")
    @PostMapping("/tickets/{id}/transfer")
    public Mono<Ticket> transfer(@PathVariable String id, ServerWebExchange exchange,
                                 @RequestBody(required = false) TransferRequest request) {
        String agent = agentId(exchange);
        String toAgent = request == null ? null : request.toAgent();
        return blocking(() -> StringUtils.hasText(toAgent)
            ? ticketService.transferToAgent(id, toAgent, TicketActorType.AGENT, agent)
            : ticketService.transferToPool(id, TicketActorType.AGENT, agent));
    }

    @Operation(summary = "标记处理完毕", description = "PROCESSING → WAITING_CONFIRM，非法状态 409")
    @PostMapping("/tickets/{id}/resolve")
    public Mono<Ticket> resolve(@PathVariable String id, ServerWebExchange exchange,
                                @RequestBody(required = false) NoteRequest request) {
        String agent = agentId(exchange);
        String note = request == null ? null : request.note();
        return blocking(() -> ticketService.markResolved(id, note, TicketActorType.AGENT, agent));
    }

    @Operation(summary = "关闭工单", description = "非法状态 409")
    @PostMapping("/tickets/{id}/close")
    public Mono<Ticket> close(@PathVariable String id, ServerWebExchange exchange,
                              @RequestBody(required = false) NoteRequest request) {
        String agent = agentId(exchange);
        String reason = request == null ? null : request.reason();
        return blocking(() -> ticketService.close(id, reason, TicketActorType.AGENT, agent));
    }

    @Operation(summary = "变更优先级", description = "任意非 CLOSED 态可改")
    @PutMapping("/tickets/{id}/priority")
    public Mono<Ticket> changePriority(@PathVariable String id, ServerWebExchange exchange,
                                       @RequestBody PriorityRequest request) {
        String agent = agentId(exchange);
        TicketPriority priority = parsePriority(request.priority());
        if (priority == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "priority required"));
        }
        return blocking(() -> ticketService.changePriority(id, priority, TicketActorType.AGENT, agent));
    }

    @Operation(summary = "变更分类", description = "任意非 CLOSED 态可改")
    @PutMapping("/tickets/{id}/category")
    public Mono<Ticket> changeCategory(@PathVariable String id, ServerWebExchange exchange,
                                       @RequestBody CategoryRequest request) {
        String agent = agentId(exchange);
        TicketCategory category = parseCategory(request.category());
        if (category == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "category required"));
        }
        return blocking(() -> ticketService.changeCategory(id, category, TicketActorType.AGENT, agent));
    }

    // ---- 内部 ----

    private String agentId(ServerWebExchange exchange) {
        String agent = exchange.getAttribute(AgentAuthWebFilter.AGENT_ID_ATTR);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return agent;
    }

    private Ticket require(String id) {
        return ticketService.find(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket not found: " + id));
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

    private TicketCategory parseCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return null;
        }
        try {
            return TicketCategory.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid category: " + category);
        }
    }

    private TicketPriority parsePriority(String priority) {
        if (!StringUtils.hasText(priority)) {
            return null;
        }
        try {
            return TicketPriority.valueOf(priority.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid priority: " + priority);
        }
    }

    private <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(this::translate);
    }

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
