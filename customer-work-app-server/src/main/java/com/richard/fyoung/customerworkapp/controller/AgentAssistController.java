package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.assist.AgentAssistService;
import com.richard.fyoung.customerwork.assist.AssistSuggestion;
import com.richard.fyoung.customerwork.quality.QualityFeedbackRecorder;
import com.richard.fyoung.customerwork.quality.QualityReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

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
    private final QualityFeedbackRecorder qualityFeedbackRecorder;

    public AgentAssistController(AgentAssistService assistService,
                                QualityFeedbackRecorder qualityFeedbackRecorder) {
        this.assistService = assistService;
        this.qualityFeedbackRecorder = qualityFeedbackRecorder;
    }

    @Operation(summary = "坐席辅助建议", description = "按用户消息给出话术/知识/工具建议")
    @PostMapping("/assist")
    public Mono<AssistSuggestion> assist(@RequestParam String message) {
        return Mono.just(assistService.suggest(message));
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
