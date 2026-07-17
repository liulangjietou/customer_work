package com.richard.fyoung.customerwork.routing;

import com.richard.fyoung.customerwork.assist.ConversationSummary;
import com.richard.fyoung.customerwork.assist.ConversationSummaryService;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.handoff.HandoffService;
import com.richard.fyoung.customerwork.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.observability.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 转人工增强器（智能路由中控·会话总结 + 工单智能分配的挂载点）：在建单后<b>异步</b>做会话摘要预生成 +
 * 工单分类 + 坐席打分推荐，把 top-N 推荐回写工单供坐席工作台展示（HITL，人工点选才真正 claim）。
 *
 * <p><b>不阻塞转人工主链路：</b>建单动作在 {@link HandoffService#create} 同步完成后即返回；本增强器把耗时的
 * LLM 摘要/分类派发到独立守护线程池执行，摘要生成慢/失败、分类/打分失败都<b>不影响转人工本身</b>。</p>
 *
 * <p><b>fail-open（与敏感词 fail-closed 相反，注释写明理由）：</b>摘要/推荐是"增强"——它们挂了不能影响
 * 转人工与对话主链路。故整个增强链路任一步失败一律 {@code log.error} 后静默退回现有行为（无推荐，坐席照常手动
 * claim），绝不把异常抛回建单调用方。开关默认关（{@code assist.summary-enabled} / {@code routing.assign-enabled}），
 * 关闭时对应环节整段跳过、零模型调用。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class HandoffCreatedEnricher {

    private static final Logger log = LoggerFactory.getLogger(HandoffCreatedEnricher.class);

    private static final String AUDIT_TYPE_SUMMARY = "conversation-summary";
    private static final String AUDIT_TYPE_ROUTING = "ticket-routing-suggestion";

    /** 独立守护线程池：转人工增强不占用调用方/事件循环线程（与 GitAssistantService 同一手法）。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "handoff-enricher-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final HandoffService handoffService;
    private final ConversationSummaryService summaryService;
    private final TicketClassifier ticketClassifier;
    private final SeatRoutingScorer seatRoutingScorer;
    private final SeatAgentStore seatAgentStore;
    private final CustomerWorkProperties properties;
    private final AuditSink auditSink;

    // handoffService 用 @Lazy 注入：本增强器与 HandoffService 互相依赖（HandoffService setter 注入本增强器做转人工触发，
    // 本增强器回写推荐又需调 HandoffService）。Spring Boot 2.6+ 默认禁止循环引用，@Lazy 注入代理打破创建期环路，
    // 且本增强器只在异步任务里真正用到 handoffService，惰性解析无副作用。
    public HandoffCreatedEnricher(@Lazy HandoffService handoffService,
                                  ConversationSummaryService summaryService,
                                  TicketClassifier ticketClassifier,
                                  SeatRoutingScorer seatRoutingScorer,
                                  SeatAgentStore seatAgentStore,
                                  CustomerWorkProperties properties,
                                  AuditSink auditSink) {
        this.handoffService = handoffService;
        this.summaryService = summaryService;
        this.ticketClassifier = ticketClassifier;
        this.seatRoutingScorer = seatRoutingScorer;
        this.seatAgentStore = seatAgentStore;
        this.properties = properties;
        this.auditSink = auditSink;
    }

    /**
     * 转人工建单后触发：把增强派发到独立线程池，<b>立即返回不阻塞</b>建单主链路。两个开关全关时连线程都不派发。
     */
    public void onHandoffCreated(HandoffTicket ticket) {
        if (ticket == null) {
            return;
        }
        boolean summaryOn = properties.getAssist().isSummaryEnabled();
        boolean assignOn = properties.getRouting().isAssignEnabled();
        if (!summaryOn && !assignOn) {
            return;
        }
        try {
            executor.submit(() -> enrich(ticket));
        } catch (Exception e) {
            // 线程池拒绝等极端情况也不能影响转人工
            log.error("[HandoffCreatedEnricher] dispatch failed, code={}, id={}",
                "HANDOFF-ENRICH-DISPATCH-FAIL", ticket.getId(), e);
        }
    }

    /**
     * 增强主体（包级可见，供单测同步调用断言）：会话摘要 → 工单分类 → 坐席打分 → 回写工单推荐。
     * 每步独立 try-catch，任一步失败不影响其余步骤，整体 fail-open。
     */
    void enrich(HandoffTicket ticket) {
        try {
            ConversationSummary summary = maybeSummarize(ticket);
            maybeAssign(ticket, summary);
        } catch (Exception e) {
            // 兜底：增强链路任何未预期异常都不能逃逸（fail-open，转人工已在主链路完成）
            log.error("[HandoffCreatedEnricher] enrich failed, code={}, id={}",
                "HANDOFF-ENRICH-FAIL", ticket.getId(), e);
        }
    }

    /** 会话摘要预生成（开关关则跳过、返回 null）。摘要服务自身 fail-open，此处只做审计埋点。 */
    private ConversationSummary maybeSummarize(HandoffTicket ticket) {
        if (!properties.getAssist().isSummaryEnabled()) {
            return null;
        }
        ConversationSummary summary = summaryService.summarize(ticket.getSessionId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("handoffId", ticket.getId());
        fields.put("sessionId", ticket.getSessionId());
        fields.put("fromModel", summary.fromModel());
        fields.put("emotion", summary.emotion());
        auditSink.record(AUDIT_TYPE_SUMMARY, fields);
        return summary;
    }

    /** 工单分类 + 坐席打分 + 回写推荐（开关关则跳过）。分类器/打分器自身 fail-open。 */
    private void maybeAssign(HandoffTicket ticket, ConversationSummary summary) {
        if (!properties.getRouting().isAssignEnabled()) {
            return;
        }
        TicketClassification classification = ticketClassifier.classify(ticket.getReason(), summary);
        List<SeatAgent> seats = seatAgentStore.findAll();
        List<SeatRecommendation> recommendations = seatRoutingScorer.score(
            classification, seats, properties.getRouting().getTopN());
        String suggestedAssignees = SeatRecommendation.toJson(recommendations);

        // 回写走 HandoffService（用其自身的 store，保证与坐席工作台读到的是同一份工单）
        handoffService.applyRoutingSuggestion(ticket.getId(), classification.category(),
            classification.requiredSkill(), classification.priority().name(), classification.emotion(),
            suggestedAssignees);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("handoffId", ticket.getId());
        fields.put("sessionId", ticket.getSessionId());
        fields.put("category", classification.category());
        fields.put("requiredSkill", classification.requiredSkill());
        fields.put("priority", classification.priority().name());
        fields.put("recommendCount", recommendations.size());
        auditSink.record(AUDIT_TYPE_ROUTING, fields);
    }
}
