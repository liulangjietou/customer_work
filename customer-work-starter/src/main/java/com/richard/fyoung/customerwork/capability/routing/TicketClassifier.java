package com.richard.fyoung.customerwork.capability.routing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.assist.ConversationSummary;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工单分类器（智能路由中控·工单智能分配的分类环节）：一次性 LLM 调用，从工单 reason + 会话摘要推断
 * 类目 / 所需技能 / 优先级 / 情绪，产出 {@link TicketClassification} 供打分器路由。
 *
 * <p><b>fail-open：</b>模型不可达 / 空响应 / 不守 JSON 格式时降级为默认分类
 * （{@link TicketClassification#fallback}，情绪优先沿用会话摘要已识别的值），<b>绝不抛异常打断转人工</b>。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class TicketClassifier {

    private static final Logger log = LoggerFactory.getLogger(TicketClassifier.class);

    private static final int MAX_CONTEXT_CHARS = 4_000;

    private static final String CLASSIFY_PROMPT =
        "你是客服工单调度专家。请根据下面的转人工原因与会话摘要，判断该工单的分类信息。\n"
        + "只输出一个 JSON 对象，不要输出任何解释文字或 Markdown 代码块标记，结构严格为：\n"
        + "{\"category\":\"工单分类(如 退款/物流/投诉/发票/账户/其它)\",\"requiredSkill\":\"处理该工单所需的坐席技能标签"
        + "(如 refund/logistics/complaint/invoice/account，无明确要求填 null)\",\"priority\":\"LOW|MEDIUM|HIGH|URGENT\","
        + "\"emotion\":\"用户情绪(平静/不满/愤怒/焦虑等)\"}\n\n";

    private final Model model;
    private final CustomerWorkProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TicketClassifier(Model model, CustomerWorkProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    /**
     * 对工单分类。<b>永不抛异常</b>：任何失败降级为默认分类返回。
     *
     * @param reason  转人工原因（工单 reason）
     * @param summary 会话摘要（可空；提供意图/情绪等辅助上下文与降级时的情绪兜底）
     */
    public TicketClassification classify(String reason, ConversationSummary summary) {
        String emotionHint = summary == null ? null : summary.emotion();
        try {
            String text = callModelOnce(buildPrompt(reason, summary));
            return parse(text, emotionHint);
        } catch (Exception e) {
            // fail-open：分类失败不阻断转人工，退回默认分类（打分器据此仍能给出兜底推荐或空推荐）
            log.error("[TicketClassifier] classify failed, code={}", "TICKET-CLASSIFY-FAIL", e);
            return TicketClassification.fallback(emotionHint);
        }
    }

    // ---------------------- private helpers ----------------------

    private String buildPrompt(String reason, ConversationSummary summary) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("转人工原因：").append(reason == null ? "(未提供)" : reason).append("\n");
        if (summary != null) {
            ctx.append("会话摘要：").append(nullToEmpty(summary.oneLineSummary())).append("\n");
            ctx.append("用户意图：").append(nullToEmpty(summary.userIntent())).append("\n");
            ctx.append("已识别情绪：").append(nullToEmpty(summary.emotion())).append("\n");
        }
        String context = ctx.toString();
        if (context.length() > MAX_CONTEXT_CHARS) {
            context = context.substring(0, MAX_CONTEXT_CHARS);
        }
        return CLASSIFY_PROMPT + context;
    }

    private TicketClassification parse(String modelText, String emotionHint) {
        String json = extractJsonObject(modelText);
        if (json == null) {
            log.error("[TicketClassifier] classify json degraded (no json), code={}", "TICKET-CLASSIFY-DEGRADE");
            return TicketClassification.fallback(emotionHint);
        }
        try {
            ClassificationPayload p = objectMapper.readValue(json, ClassificationPayload.class);
            String category = StringUtils.hasText(p.category) ? p.category.trim() : TicketClassification.DEFAULT_CATEGORY;
            String requiredSkill = normalizeSkill(p.requiredSkill);
            TicketPriority priority = TicketPriority.fromName(p.priority);
            String emotion = StringUtils.hasText(p.emotion) ? p.emotion.trim()
                : (StringUtils.hasText(emotionHint) ? emotionHint : TicketClassification.DEFAULT_EMOTION);
            return new TicketClassification(category, requiredSkill, priority, emotion);
        } catch (Exception e) {
            log.error("[TicketClassifier] classify json deserialize failed, code={}", "TICKET-CLASSIFY-DEGRADE", e);
            return TicketClassification.fallback(emotionHint);
        }
    }

    /** 归一所需技能：空白 / 字面 "null" / "none" 一律视为无技能要求（null）。 */
    private String normalizeSkill(String skill) {
        if (!StringUtils.hasText(skill)) {
            return null;
        }
        String trimmed = skill.trim();
        if ("null".equalsIgnoreCase(trimmed) || "none".equalsIgnoreCase(trimmed) || "无".equals(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private String callModelOnce(String prompt) {
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text(prompt).build())
            .build();
        long timeout = Math.max(1, properties.getRouting().getClassifyTimeoutSeconds());
        List<ChatResponse> responses = model.stream(List.of(userMsg), List.of(), GenerateOptions.builder().build())
            .collectList()
            .block(Duration.ofSeconds(timeout));
        if (responses == null || responses.isEmpty()) {
            throw new IllegalStateException("classify model returned empty response");
        }
        String text = responses.stream()
            .flatMap(response -> response.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("classify model returned no text content");
        }
        return text.trim();
    }

    private String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : null;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 模型 JSON 反序列化载体（贫血，仅解析用）。 */
    private static final class ClassificationPayload {
        public String category;
        public String requiredSkill;
        public String priority;
        public String emotion;
    }
}
