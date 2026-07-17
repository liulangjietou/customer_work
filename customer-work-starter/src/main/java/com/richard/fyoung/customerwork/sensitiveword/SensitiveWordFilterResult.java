package com.richard.fyoung.customerwork.sensitiveword;

import java.util.Collections;
import java.util.List;

/**
 * 一次过滤的结果（不可变）。
 *
 * @param originalText 原始文本
 * @param maskedText   已对 {@code MASK} 命中片段打码后的文本（无 MASK 命中时等于原文）
 * @param hits         全部命中（归一化文本内偏移）
 * @param decision     整体决策：多命中时取最高优先级动作；无命中为 {@code null}（放行）
 * @author owlzhangfq@gmail.com
 */
public record SensitiveWordFilterResult(String originalText, String maskedText,
                                        List<SensitiveWordHit> hits, SensitiveWordAction decision) {

    /** 无命中的放行结果。 */
    public static SensitiveWordFilterResult pass(String text) {
        return new SensitiveWordFilterResult(text, text, Collections.emptyList(), null);
    }

    public boolean hasHit() {
        return decision != null;
    }

    public boolean blocked() {
        return decision == SensitiveWordAction.BLOCK;
    }

    public boolean masked() {
        return decision == SensitiveWordAction.MASK;
    }
}
