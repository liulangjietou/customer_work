package com.richard.fyoung.customerwork.capability.knowledgegap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 知识盲区：一个反复检索不到知识的问题。
 *
 * <p>这份数据本来唾手可得——检索未命中时记一笔就行——但此前没人记，于是"该补哪些知识"
 * 只能靠运营拍脑袋想。而拍脑袋想出来的往往是他们自己关心的，不是用户实际在问的。</p>
 *
 * <p><b>按次数聚合而非逐条流水</b>：用户要的答案是"哪些问题<b>反复</b>查不到"，
 * 一条只出现过一次的问法没有补知识的价值，而未命中的绝对量在客服场景会非常大，
 * 逐条落库既贵又淹没重点。故以问题原文为键累加计数。</p>
 *
 * @param questionHash  问题原文的 SHA-256（主键；问题可能很长，不适合直接做键）
 * @param question      问题原文（截断保存，运营要看的是这个）
 * @param scopeId       运营统计分区键（OpsScopeResolver 取当前租户，无上下文回落 default）
 * @param missCount     累计未命中次数——排序依据，越大越该优先补
 * @param firstSeenAtMs 首次出现时间戳（毫秒）
 * @param lastSeenAtMs  最近一次出现时间戳（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeGap(
    String questionHash,
    String question,
    String scopeId,
    long missCount,
    long firstSeenAtMs,
    long lastSeenAtMs
) {

    /** 问题原文入库上限：超长的多半是粘贴进来的大段文本，截断不影响识别。 */
    public static final int MAX_QUESTION_LENGTH = 500;

    /** 新建一条盲区记录（首次未命中）。 */
    public static KnowledgeGap firstMiss(String question, String scopeId, long nowMs) {
        String normalized = normalize(question);
        return new KnowledgeGap(hashOf(normalized), normalized, scopeId, 1L, nowMs, nowMs);
    }

    /** 归一化：去首尾空白并截断——同一个问题不该因为多打了个空格就被算成两条。 */
    public static String normalize(String question) {
        String trimmed = question == null ? "" : question.trim();
        return trimmed.length() > MAX_QUESTION_LENGTH
            ? trimmed.substring(0, MAX_QUESTION_LENGTH) : trimmed;
    }

    /** 问题原文的 SHA-256 十六进制串（规避长文本做主键的长度限制，同 {@code cw_harness_memory} 手法）。 */
    public static String hashOf(String normalizedQuestion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedQuestion.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里；真走到了说明 JRE 被裁剪过，属于部署事故
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 再次未命中：计数 +1 并刷新最近出现时间。 */
    public KnowledgeGap hitAgain(long nowMs) {
        return new KnowledgeGap(questionHash, question, scopeId, missCount + 1, firstSeenAtMs, nowMs);
    }
}
