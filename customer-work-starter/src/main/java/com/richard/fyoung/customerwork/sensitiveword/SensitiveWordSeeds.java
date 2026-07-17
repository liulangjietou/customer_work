package com.richard.fyoung.customerwork.sensitiveword;

import java.util.List;

/**
 * 敏感词演示种子（<b>脱敏占位词，非真实违禁词</b>），与 {@code customer-work-schema.sql} 的
 * {@code INSERT IGNORE} 保持一致：memory 模式由 {@link InMemorySensitiveWordStore} 装载，jdbc 模式由建表脚本装载。
 *
 * <p>覆盖三种处置动作与多类目，便于演示"拦截 / 打码 / 复核"分流。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SensitiveWordSeeds {

    private SensitiveWordSeeds() {
    }

    /** 演示种子词（占位）。 */
    public static List<SensitiveWord> demoWords() {
        return List.of(
            SensitiveWord.of("测试敏感词A", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK),
            SensitiveWord.of("涉政占位", SensitiveWordCategory.POLITICS, SensitiveWordAction.BLOCK),
            SensitiveWord.of("辱骂占位", SensitiveWordCategory.ABUSE, SensitiveWordAction.BLOCK),
            SensitiveWord.of("竞品XX", SensitiveWordCategory.COMPETITOR, SensitiveWordAction.MASK),
            SensitiveWord.of("复核占位", SensitiveWordCategory.CUSTOM, SensitiveWordAction.REVIEW));
    }
}
