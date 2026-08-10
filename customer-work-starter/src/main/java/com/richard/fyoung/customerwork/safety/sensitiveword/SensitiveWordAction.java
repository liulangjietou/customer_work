package com.richard.fyoung.customerwork.safety.sensitiveword;

/**
 * 敏感词处置动作（命中后如何处理）。
 *
 * <p>{@code severity} 表达优先级：同一段文本命中多个词、动作不一时取<b>最高优先级</b>的动作作为整体决策
 * （{@code BLOCK} &gt; {@code MASK} &gt; {@code REVIEW}）——例如一句话里既有需打码词又有需拦截词，应整体拦截。</p>
 * @author owlzhangfq@gmail.com
 */
public enum SensitiveWordAction {

    /** 放行但标记，供人工/异步复核（不改写、不拦截）。 */
    REVIEW(1),

    /** 命中片段打码后放行（入站进模型前 / 出站回吐前都可用）。 */
    MASK(2),

    /** 命中即硬拦截：入站不进模型、出站替换为安全兜底话术。 */
    BLOCK(3);

    private final int severity;

    SensitiveWordAction(int severity) {
        this.severity = severity;
    }

    /** 优先级（越大越严格），用于多命中时的整体决策仲裁。 */
    public int severity() {
        return severity;
    }
}
