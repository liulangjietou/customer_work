package com.richard.fyoung.customerwork.sensitiveword;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 敏感词命中记录（一次消息扫描产生一条，不可变值对象）。
 *
 * <p>一条消息可能同时命中多个词，故 {@code words} / {@code categories} 是列表；{@code action} 是这条消息的
 * <b>整体决策</b>（BLOCK &gt; MASK &gt; REVIEW 取最高档），与中间件实际执行的处置一致。</p>
 *
 * <p>{@code snippet} 是原文片段（截断），供运营判断上下文；命中词本身已在 {@code words} 明文留存，
 * 故片段不再打码，否则运营看不出命中缘由。是否留存整条由 {@code sensitive-word.hit-log.enabled} 控制。</p>
 *
 * @param direction  命中方向（入站/出站）
 * @param action     整体决策
 * @param words      命中词面（原词，非归一化后）
 * @param categories 命中类目（去重）
 * @param agentName  智能体名
 * @param sessionId  会话 ID（可空）
 * @param userId     用户 ID（可空）
 * @param snippet    原文片段（已截断）
 * @param createdAtMs 命中时刻（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record SensitiveWordHitRecord(SensitiveWordHitDirection direction,
                                     SensitiveWordAction action,
                                     List<String> words,
                                     List<String> categories,
                                     String agentName,
                                     String sessionId,
                                     String userId,
                                     String snippet,
                                     long createdAtMs) {

    /**
     * 由一次过滤结果构造命中记录：从命中列表提取词面与类目（去重保序）。
     *
     * @param snippetMaxLength 原文片段保留长度上限（&lt;=0 表示不留存片段）
     */
    public static SensitiveWordHitRecord of(SensitiveWordHitDirection direction,
                                            SensitiveWordFilterResult result,
                                            String agentName,
                                            String sessionId,
                                            String userId,
                                            int snippetMaxLength) {
        List<String> words = new ArrayList<>();
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (SensitiveWordHit hit : result.hits()) {
            SensitiveWord word = hit.word();
            if (word == null) {
                continue;
            }
            words.add(word.getWord());
            if (word.getCategory() != null) {
                categories.add(word.getCategory().name());
            }
        }
        return new SensitiveWordHitRecord(direction, result.decision(), words, new ArrayList<>(categories),
            agentName, sessionId, userId, truncate(result.originalText(), snippetMaxLength),
            System.currentTimeMillis());
    }

    /** 截断原文片段；长度上限非正表示不留存。 */
    private static String truncate(String text, int maxLength) {
        if (text == null || maxLength <= 0) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
