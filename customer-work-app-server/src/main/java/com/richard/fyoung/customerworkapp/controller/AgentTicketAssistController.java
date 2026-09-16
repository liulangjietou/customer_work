package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.assist.ConversationSummaryService;
import com.richard.fyoung.customerwork.capability.assist.TicketAssistView;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 沿用坐席令牌和工单读取权限，从当前租户的真实工单推导会话，不接受浏览器指定会话。 */
@RestController
@RequestMapping("/api/customer/agent/tickets")
public class AgentTicketAssistController {
    private final TicketService tickets;
    private final ConversationSummaryService summaries;

    public AgentTicketAssistController(TicketService tickets, ConversationSummaryService summaries) {
        this.tickets = tickets;
        this.summaries = summaries;
    }

    /** 只读当前有效摘要或规则整理；读取失败保留错误语义，不额外调用模型。 */
    @GetMapping("/{id}/assist")
    public Mono<TicketAssistView> assist(@PathVariable String id) {
        return Mono.deferContextual(context -> {
            String tenant = context.getOrDefault(TenantContextThreadLocalAccessor.KEY, TenantContext.get());
            return Mono.fromCallable(() -> TenantContext.callWith(tenant, () -> {
                var ticket = tickets.find(id).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在或已不可访问"));
                return new TicketAssistView(ticket.getId(), summaries.readCurrent(ticket.getSessionId()));
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
