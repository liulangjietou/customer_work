package com.richard.fyoung.customerwork.capability.knowledgegap;

import java.util.Set;

/** 当前分类事实；规则建议与人工结论分别标识，人工修改由独立审计记录保留。 */
public record KnowledgeGapClassification(KnowledgeGapCategory category, KnowledgeGapPriority priority,
                                         Origin origin, String reason, long revision,
                                         String reviewedBy, Long reviewedAtMs) {
    public enum Origin { UNKNOWN, RULE, MANUAL }
    private static final Set<String> GREETINGS = Set.of(
        "你好", "您好", "你好呀", "你好啊", "您好呀", "早上好", "早上好呀", "晚上好", "下午好", "hello", "hi");
    private static final Set<String> CLOCK_QUERIES = Set.of(
        "今天几号", "今天是几号", "今天星期几", "今天是星期几", "今天周几", "现在几点", "现在几点钟", "今天是什么日期");

    /** 只匹配完整的问候和时钟问题；包含业务内容或语义不明的提问继续待分类。 */
    public static KnowledgeGapClassification suggest(String question) {
        String text = question == null ? "" : question.trim().toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[\\s，。！？!?]", "");
        if (GREETINGS.contains(text)) {
            return suggested(KnowledgeGapCategory.NON_BUSINESS, "完整提问符合常见问候规则，请按实际业务复核");
        }
        if (CLOCK_QUERIES.contains(text)) {
            return suggested(KnowledgeGapCategory.REALTIME, "完整提问仅询问当前日期或时间，静态知识无法保证实时性");
        }
        return new KnowledgeGapClassification(KnowledgeGapCategory.PENDING, KnowledgeGapPriority.NORMAL,
            Origin.UNKNOWN, "只有检索未命中证据，尚不足以判断原因", 0, null, null);
    }

    /** 兼容旧快照时，达到存储上限的文本可能缺少业务后半句，保留待分类。 */
    public static KnowledgeGapClassification suggestFromStoredQuestion(String question) {
        return suggest(question != null && question.length() >= KnowledgeGap.MAX_QUESTION_LENGTH
            ? null : question);
    }

    private static KnowledgeGapClassification suggested(KnowledgeGapCategory category, String reason) {
        return new KnowledgeGapClassification(category, KnowledgeGapPriority.NORMAL, Origin.RULE,
            reason, 0, null, null);
    }
}
