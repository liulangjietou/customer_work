package com.richard.fyoung.customerwork.capability.assist;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessageStore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 会话总结建议服务（智能路由中控·会话总结）：对整段会话历史做一次性 LLM 总结，产出结构化
 * {@link ConversationSummary} 供接手坐席快速了解上下文。
 *
 * <p><b>fail-open（与敏感词的 fail-closed 相反）：</b>总结是"增强"能力——模型不可达 / 空响应 / 不守 JSON
 * 格式时，一律降级到规则版 {@link AgentAssistService} 的建议 + 历史原文进摘要（{@code fromModel=false}），
 * <b>绝不抛异常打断转人工与对话主链路</b>。这与"总结/推荐挂了不能影响转人工本身"的定位一致。</p>
 *
 * <p>一次性调用手法与 {@code ModelVisionOcrService} / admin 侧 {@code GitAssistantService.callModelOnce}
 * 一致（单条 user 消息、不带工具、收集全部文本块拼接）。结果按 sessionId 进有界缓存，供转人工时预生成、
 * 坐席工作台随后免二次 LLM 调用拉取。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    /** 送入模型的历史文本上限，超过截断，避免撑爆上下文。 */
    private static final int MAX_TRANSCRIPT_CHARS = 12_000;
    /** 一句话摘要降级时的原文截断长度。 */
    private static final int FALLBACK_SUMMARY_MAX_CHARS = 120;

    private static final String SUMMARY_PROMPT =
        "你是资深客服主管。请阅读下面这段客服会话记录，为即将接手的人工坐席做一份结构化摘要。\n"
        + "只输出一个 JSON 对象，不要输出任何解释文字或 Markdown 代码块标记，结构严格为：\n"
        + "{\"oneLineSummary\":\"一句话概括\",\"userIntent\":\"用户核心诉求\",\"emotion\":\"用户情绪(平静/不满/愤怒/焦虑等)\","
        + "\"triedSolutions\":[\"已尝试的方案1\"],\"pendingIssues\":[\"仍待解决的问题1\"],"
        + "\"suggestedNextStep\":\"给坐席的下一步建议\",\"suggestedReply\":\"建议的开场话术\"}\n"
        + "列表字段没有内容时返回空数组。会话记录如下：\n\n";

    private final Model model;
    private final ChatMessageStore chatMessageStore;
    private final AgentAssistService assistService;
    private final CustomerWorkProperties properties;

    /** 宽松 JSON 解析（忽略模型多吐的未知字段），窄用途、不依赖容器注入。 */
    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** sessionId → 最近一次摘要缓存（有界，供坐席工作台免二次 LLM 拉取转人工时预生成的结果）。 */
    private final Map<String, ConversationSummary> latestBySession;

    public ConversationSummaryService(Model model, ChatMessageStore chatMessageStore,
                                      AgentAssistService assistService, CustomerWorkProperties properties) {
        this.model = model;
        this.chatMessageStore = chatMessageStore;
        this.assistService = assistService;
        this.properties = properties;
        int cap = Math.max(1, properties.getAssist().getSummaryCacheMaxSessions());
        // 按插入序淘汰最旧：accessOrder=false 的 LinkedHashMap + removeEldestEntry，外层 synchronized 保证线程安全
        this.latestBySession = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ConversationSummary> eldest) {
                return size() > cap;
            }
        });
    }

    /**
     * 对指定会话做整段总结。<b>永不抛异常</b>：任何失败都降级为规则版摘要返回。结果同时进缓存供
     * {@link #findLatest} 免二次调用拉取。
     */
    public ConversationSummary summarize(String sessionId) {
        List<ChatMessage> history = loadHistory(sessionId);
        String transcript = toTranscript(history);
        String lastUserMessage = lastUserMessage(history);
        ConversationSummary summary;
        try {
            summary = summarizeByModel(transcript, lastUserMessage);
        } catch (Exception e) {
            // fail-open：模型调用失败不阻断转人工/对话主链路，降级到规则版建议
            log.error("[ConversationSummaryService] summarize failed, code={}, session={}",
                "SUMMARY-GENERATE-FAIL", sessionId, e);
            summary = fallbackSummary(transcript, lastUserMessage);
        }
        cache(sessionId, summary);
        return summary;
    }

    /** 拉取转人工时预生成并缓存的最近一次摘要（无则空，坐席工作台可据此决定是否触发按需生成）。 */
    public Optional<ConversationSummary> findLatest(String sessionId) {
        return Optional.ofNullable(latestBySession.get(sessionId));
    }

    // ---------------------- private helpers ----------------------

    private List<ChatMessage> loadHistory(String sessionId) {
        int limit = Math.max(1, properties.getAssist().getSummaryHistoryLimit());
        try {
            return chatMessageStore.findBySession(sessionId, null, limit);
        } catch (Exception e) {
            log.error("[ConversationSummaryService] load history failed, code={}, session={}",
                "SUMMARY-HISTORY-LOAD-FAIL", sessionId, e);
            return List.of();
        }
    }

    /** 一次性 LLM 总结：拼接历史 → 单条 user 消息 → 收集文本 → 解析 JSON；空历史/空响应/解析失败一律走降级。 */
    private ConversationSummary summarizeByModel(String transcript, String lastUserMessage) {
        if (!StringUtils.hasText(transcript)) {
            // 无历史：不调用模型，直接给规则降级摘要
            return fallbackSummary(transcript, lastUserMessage);
        }
        String text = callModelOnce(SUMMARY_PROMPT + truncate(transcript, MAX_TRANSCRIPT_CHARS));
        Optional<ConversationSummary> parsed = parse(text);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        // 模型没吐合法 JSON：降级但保留原文进摘要，便于坐席仍能看到模型的自由文本输出
        log.error("[ConversationSummaryService] summary json parse degraded, code={}", "SUMMARY-LLM-DEGRADE");
        ConversationSummary fallback = fallbackSummary(transcript, lastUserMessage);
        String rawOneLine = StringUtils.hasText(text) ? truncate(text.trim(), FALLBACK_SUMMARY_MAX_CHARS)
            : fallback.oneLineSummary();
        return new ConversationSummary(rawOneLine, fallback.userIntent(), fallback.emotion(),
            fallback.triedSolutions(), fallback.pendingIssues(), fallback.suggestedNextStep(),
            fallback.suggestedReply(), false);
    }

    /** 解析模型返回的摘要 JSON（容忍前后夹带说明/代码块标记）；结构不完整或异常返回 empty 交由上层降级。 */
    private Optional<ConversationSummary> parse(String modelText) {
        String json = extractJsonObject(modelText);
        if (json == null) {
            return Optional.empty();
        }
        try {
            SummaryPayload p = objectMapper.readValue(json, SummaryPayload.class);
            if (!StringUtils.hasText(p.oneLineSummary)) {
                return Optional.empty();
            }
            return Optional.of(new ConversationSummary(
                p.oneLineSummary, p.userIntent, p.emotion,
                nullSafe(p.triedSolutions), nullSafe(p.pendingIssues),
                p.suggestedNextStep, p.suggestedReply, true));
        } catch (Exception e) {
            log.error("[ConversationSummaryService] summary json deserialize failed, code={}", "SUMMARY-LLM-DEGRADE", e);
            return Optional.empty();
        }
    }

    /** 规则降级摘要：复用规则版 {@link AgentAssistService} 的建议，历史/末条用户消息进 oneLineSummary。 */
    private ConversationSummary fallbackSummary(String transcript, String lastUserMessage) {
        AssistSuggestion suggestion = assistService.suggest(lastUserMessage);
        String oneLine = StringUtils.hasText(lastUserMessage)
            ? "用户最新诉求：" + truncate(lastUserMessage, FALLBACK_SUMMARY_MAX_CHARS)
            : (StringUtils.hasText(transcript) ? truncate(transcript, FALLBACK_SUMMARY_MAX_CHARS) : "暂无会话历史");
        return new ConversationSummary(oneLine, lastUserMessage, null,
            List.of(), List.of(), suggestion.knowledgeHint(), suggestion.suggestedReply(), false);
    }

    /** 一次性模型调用：单条 user 消息、不带工具，取全部返回文本块拼接结果。空响应抛异常交由上层降级。 */
    private String callModelOnce(String prompt) {
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text(prompt).build())
            .build();
        long timeout = Math.max(1, properties.getAssist().getSummaryTimeoutSeconds());
        List<ChatResponse> responses = model.stream(List.of(userMsg), List.of(), GenerateOptions.builder().build())
            .collectList()
            .block(Duration.ofSeconds(timeout));
        if (responses == null || responses.isEmpty()) {
            throw new IllegalStateException("summary model returned empty response");
        }
        String text = responses.stream()
            .flatMap(response -> response.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("summary model returned no text content");
        }
        return text.trim();
    }

    private String toTranscript(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        return history.stream()
            .map(m -> roleLabel(m.senderType()) + "：" + (m.content() == null ? "" : m.content()))
            .collect(Collectors.joining("\n"));
    }

    private String lastUserMessage(List<ChatMessage> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m.senderType() == TicketActorType.USER && StringUtils.hasText(m.content())) {
                return m.content();
            }
        }
        return "";
    }

    private String roleLabel(TicketActorType type) {
        if (type == null) {
            return "未知";
        }
        switch (type) {
            case USER:
                return "用户";
            case BOT:
                return "机器人";
            case AGENT:
                return "坐席";
            default:
                return "系统";
        }
    }

    private String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : null;
    }

    private List<String> nullSafe(List<String> list) {
        return list == null ? List.of() : new ArrayList<>(list);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private void cache(String sessionId, ConversationSummary summary) {
        if (StringUtils.hasText(sessionId) && summary != null) {
            latestBySession.put(sessionId, summary);
        }
    }

    /** 模型 JSON 反序列化载体（贫血，仅解析用）：字段名与提示词约定的 JSON key 一一对应。 */
    private static final class SummaryPayload {
        public String oneLineSummary;
        public String userIntent;
        public String emotion;
        public List<String> triedSolutions;
        public List<String> pendingIssues;
        public String suggestedNextStep;
        public String suggestedReply;
    }
}
