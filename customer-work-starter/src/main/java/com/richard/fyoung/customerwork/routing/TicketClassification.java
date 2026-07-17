package com.richard.fyoung.customerwork.routing;

/**
 * 工单分类结果（LLM 一次性分类的产物）。
 *
 * <p>由 {@link TicketClassifier} 从工单 reason + 会话摘要/历史推断得到；模型不守格式时降级为默认分类
 * （{@code GENERAL / null 技能 / MEDIUM / UNKNOWN}），fail-open 不打断转人工。</p>
 *
 * @param category      工单分类（如 退款/物流/投诉/发票/GENERAL）
 * @param requiredSkill 所需坐席技能标签（可空，null 表示无硬性技能要求）
 * @param priority      优先级
 * @param emotion       用户情绪
 * @author owlzhangfq@gmail.com
 */
public record TicketClassification(String category, String requiredSkill,
                                   TicketPriority priority, String emotion) {

    /** 默认分类（分类失败/降级时使用）：无技能要求、中优先级、情绪未知。 */
    public static final String DEFAULT_CATEGORY = "GENERAL";
    public static final String DEFAULT_EMOTION = "UNKNOWN";

    /** 降级默认分类：emotion 优先取会话摘要已识别的情绪，无则 UNKNOWN。 */
    public static TicketClassification fallback(String emotionHint) {
        String emotion = (emotionHint == null || emotionHint.isEmpty()) ? DEFAULT_EMOTION : emotionHint;
        return new TicketClassification(DEFAULT_CATEGORY, null, TicketPriority.MEDIUM, emotion);
    }
}
