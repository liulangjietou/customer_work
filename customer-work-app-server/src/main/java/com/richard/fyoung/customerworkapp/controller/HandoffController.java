package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerworkapp.web.HttpErrors;
import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffStatus;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.capability.routing.SeatRecommendation;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 人机切换工单端点（AI→人工接管 → 人工→AI 回收 闭环）。
 *
 * <p>{@code transferToHuman} 工具触发创建 {@code PENDING} 工单后，坐席工作台经本端点
 * {@link #list}/{@link #get} 查询待接单队列，{@link #claim} 接单开始处理，处理完毕
 * {@link #resolve} 结案（会话可回收给 AI 续接）——取代此前"转人工只生成一句话术"的空闭环。</p>
 *
 * <p>全部端点由 {@link AgentAuthWebFilter} 校验坐席 HMAC 令牌；操作员身份只取服务端验签结果，
 * 不信任客户端请求参数，避免冒充其他坐席接单或结案。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/handoffs")
@Tag(name = "人机切换", description = "AI 转人工工单查询 / 接单 / 结案回收")
public class HandoffController {

    private static final String AUDIT_TYPE_HANDOFF_DECISION = "handoff-decision";

    private final HandoffService handoffService;
    private final AuditSink auditSink;

    public HandoffController(HandoffService handoffService, AuditSink auditSink) {
        this.handoffService = handoffService;
        this.auditSink = auditSink;
    }

    @Operation(summary = "工单列表", description = "可选 ?status=pending/claimed/resolved 过滤")
    @GetMapping
    public Mono<List<HandoffTicket>> list(@RequestParam(required = false) String status) {
        if (StringUtils.hasText(status)) {
            return Mono.fromCallable(() -> handoffService.listByStatus(parseStatus(status)));
        }
        return Mono.fromCallable(handoffService::list);
    }

    @Operation(summary = "工单详情")
    @GetMapping("/{id}")
    public Mono<HandoffTicket> get(@PathVariable String id) {
        return Mono.justOrEmpty(handoffService.find(id))
            .switchIfEmpty(Mono.error(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "handoff not found: " + id)));
    }

    @Operation(summary = "工单智能分配推荐", description = "返回建单时预生成的 top-N 推荐坐席（含打分与可解释理由）供坐席工作台展示；"
        + "坐席人工点选后仍走 claim 接单（HITL，不自动派单）。未开启智能分配 / 推荐尚未生成 / 无候选时返回空列表（fail-open）。")
    @GetMapping("/{id}/routing-suggestions")
    public Mono<List<SeatRecommendation>> routingSuggestions(@PathVariable String id) {
        return Mono.justOrEmpty(handoffService.find(id))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "handoff not found: " + id)))
            .map(ticket -> SeatRecommendation.parseList(ticket.getSuggestedAssignees()));
    }

    @Operation(summary = "坐席接单", description = "PENDING → CLAIMED，重复接单返回 409")
    @PostMapping("/{id}/claim")
    public Mono<HandoffTicket> claim(@PathVariable String id,
                                     ServerWebExchange exchange) {
        String operator = requireAgent(exchange);
        return Mono.fromCallable(() -> handoffService.claim(id, operator))
            .doOnNext(t -> audit(t, "claim", operator, null))
            .onErrorMap(HttpErrors::translate);
    }

    @Operation(summary = "结案回收给 AI", description = "CLAIMED → RESOLVED，未接单先结案返回 409")
    @PostMapping("/{id}/resolve")
    public Mono<HandoffTicket> resolve(@PathVariable String id,
                                       @RequestParam(required = false) String note,
                                       ServerWebExchange exchange) {
        String operator = requireAgent(exchange);
        return Mono.fromCallable(() -> handoffService.resolve(id, note, operator))
            .doOnNext(t -> audit(t, "resolve", operator, note))
            .onErrorMap(HttpErrors::translate);
    }

    /** 工单流转审计留痕（合规追溯：谁在何时对哪张工单做了何种流转）。 */
    private void audit(HandoffTicket ticket, String decision, String operator, String note) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("handoffId", ticket.getId());
        fields.put("sessionId", ticket.getSessionId());
        fields.put("decision", decision);
        if (operator != null) {
            fields.put("operator", operator);
        }
        if (note != null) {
            fields.put("note", note);
        }
        auditSink.record(AUDIT_TYPE_HANDOFF_DECISION, fields);
    }

    /** 领域异常 → HTTP 状态：不存在 404，状态机冲突 409。 */

    private HandoffStatus parseStatus(String status) {
        try {
            return HandoffStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
        }
    }

    private String requireAgent(ServerWebExchange exchange) {
        String agentId = exchange.getAttribute(AgentAuthWebFilter.AGENT_ID_ATTR);
        if (!StringUtils.hasText(agentId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent principal missing");
        }
        return agentId;
    }
}
