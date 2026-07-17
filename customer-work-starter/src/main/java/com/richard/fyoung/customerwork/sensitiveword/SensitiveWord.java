package com.richard.fyoung.customerwork.sensitiveword;

/**
 * 敏感词领域对象（不可变值对象）。
 *
 * <p>{@code word} 为词面：存储层保存人读原词，{@link SensitiveWordFilter} 构建自动机前统一经
 * {@link TextNormalizer#normalize} 归一化（使 {@code 敏*感*词} / 全角 / 大小写变体也能命中），故喂给
 * {@link AhoCorasickMatcher} 的实例其 {@code word} 已是归一化词面。{@code category} / {@code action} 决定
 * 命中后的处置；{@code id} 仅 JDBC 实现回填，进程内实现可为 {@code null}。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SensitiveWord {

    private final Long id;
    private final String word;
    private final SensitiveWordCategory category;
    private final SensitiveWordAction action;
    private final boolean enabled;

    public SensitiveWord(Long id, String word, SensitiveWordCategory category,
                         SensitiveWordAction action, boolean enabled) {
        this.id = id;
        this.word = word;
        this.category = category;
        this.action = action;
        this.enabled = enabled;
    }

    /** 便捷构造：无 id、默认启用。 */
    public static SensitiveWord of(String word, SensitiveWordCategory category, SensitiveWordAction action) {
        return new SensitiveWord(null, word, category, action, true);
    }

    public Long getId() {
        return id;
    }

    public String getWord() {
        return word;
    }

    public SensitiveWordCategory getCategory() {
        return category;
    }

    public SensitiveWordAction getAction() {
        return action;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
