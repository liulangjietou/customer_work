package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.assist.AgentAssistService;
import com.richard.fyoung.customerwork.capability.assist.AssistSuggestion;
import com.richard.fyoung.customerwork.capability.assist.ConversationSummary;
import com.richard.fyoung.customerwork.capability.assist.ConversationSummaryService;
import com.richard.fyoung.customerwork.capability.quality.QualityFeedbackRecorder;
import com.richard.fyoung.customerwork.capability.quality.QualityReport;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 坐席辅助 + 会话质检端点（借鉴 AliGo 坐席辅助 / 质检）。
 *
 * <p>{@code /assist} 给人工坐席实时话术建议（不直接发用户）；{@code /quality/inspect} 对回复做合规质检，
 * 不通过时经 {@link QualityFeedbackRecorder} 沉淀为可追溯的事实流水（数据飞轮：供离线复盘）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer")
@Tag(name = "坐席辅助与质检", description = "实时话术建议 / 会话合规质检")
public class AgentAssistController {

    private static final String DEFAULT_SESSION_ID = "unknown";

    private final AgentAssistService assistService;
    private final ConversationSummaryService conversationSummaryService;
    private final QualityFeedbackRecorder qualityFeedbackRecorder;

    public AgentAssistController(AgentAssistService assistService,
                                ConversationSummaryService conversationSummaryService,
                                QualityFeedbackRecorder qualityFeedbackRecorder) {
        this.assistService = assistService;
        this.conversationSummaryService = conversationSummaryService;
        this.qualityFeedbackRecorder = qualityFeedbackRecorder;
    }

    @Operation(summary = "坐席辅助建议", description = "按用户消息给出话术/知识/工具建议")
    @PostMapping("/assist")
    public Mono<AssistSuggestion> assist(@RequestParam String message) {
        return Mono.just(assistService.suggest(message));
    }

    @Operation(summary = "会话总结建议", description = "对整段会话历史做一次性 LLM 结构化总结（意图/情绪/已尝试/待解决/建议）；"
        + "模型不可用时使用原文和规则建议；历史读取失败返回错误。摘要附实际使用的消息依据。")
    @GetMapping("/assist/summary")
    public Mono<ConversationSummary> summary(@RequestParam String sessionId) {
        // 此入口属于服务凭证接口；跨线程显式还原入口上下文，不接受调用方自报租户或主体。
        return Mono.deferContextual(context -> {
            String tenant = context.getOrDefault(TenantContextThreadLocalAccessor.KEY, TenantContext.get());
            QuotaSubject subject = context.getOrDefault(QuotaSubjectContextThreadLocalAccessor.KEY,
                QuotaSubjectContext.get());
            return Mono.fromCallable(() -> TenantContext.callWith(tenant,
                () -> QuotaSubjectContext.callWith(subject, () -> conversationSummaryService.summarize(sessionId))))
                .subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Operation(summary = "会话质检", description = "对一组坐席/Agent 回复做合规与服务规范打分；"
        + "不通过时沉淀事实流水供离线复盘（数据飞轮）")
    @PostMapping("/quality/inspect")
    public Mono<QualityReport> inspect(@RequestParam(required = false) String sessionId,
                                       @RequestBody List<String> replies) {
        String resolvedSessionId = StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
        return Mono.just(qualityFeedbackRecorder.inspectAndRecord(resolvedSessionId, replies));
    }
}
