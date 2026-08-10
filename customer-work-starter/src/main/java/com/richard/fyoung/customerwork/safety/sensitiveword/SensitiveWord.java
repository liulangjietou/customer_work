package com.richard.fyoung.customerwork.safety.sensitiveword;

/**
 * 敏感词领域对象（不可变值对象）。
 *
 * <p><b>两个词面，各司其职</b>：{@code word} 是运营在词库里维护的人读原词，任何对外呈现（命中日志、
 * 审计、后台展示）都用它；{@code matchWord} 是喂给 {@link AhoCorasickMatcher} 的归一化词面，由
 * {@link SensitiveWordFilter} 构建自动机时经 {@link TextNormalizer#normalize} 生成（使 {@code 敏*感*词} /
 * 全角 / 大小写变体也能命中）。两者分开存是必须的——早先构建时直接用归一化词面覆盖 {@code word}，
 * 结果命中记录里落的是 {@code 测试敏感词a} 这种归一化产物，跟运营在词库看到的 {@code 测试敏感词A} 对不上。</p>
 *
 * <p>{@code category} / {@code action} 决定命中后的处置；{@code id} 仅 JDBC 实现回填，进程内实现可为 {@code null}。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SensitiveWord {

    private final Long id;
    private final String word;
    private final String matchWord;
    private final SensitiveWordCategory category;
    private final SensitiveWordAction action;
    private final boolean enabled;

    public SensitiveWord(Long id, String word, SensitiveWordCategory category,
                         SensitiveWordAction action, boolean enabled) {
        this(id, word, word, category, action, enabled);
    }

    private SensitiveWord(Long id, String word, String matchWord, SensitiveWordCategory category,
                          SensitiveWordAction action, boolean enabled) {
        this.id = id;
        this.word = word;
        this.matchWord = matchWord;
        this.category = category;
        this.action = action;
        this.enabled = enabled;
    }

    /** 便捷构造：无 id、默认启用。 */
    public static SensitiveWord of(String word, SensitiveWordCategory category, SensitiveWordAction action) {
        return new SensitiveWord(null, word, category, action, true);
    }

    /** 派生出"带归一化匹配词面"的副本，原词面保持不变（构建自动机时用）。 */
    public SensitiveWord withMatchWord(String normalizedWord) {
        return new SensitiveWord(id, word, normalizedWord, category, action, true);
    }

    public Long getId() {
        return id;
    }

    /** 人读原词（对外呈现一律用它）。 */
    public String getWord() {
        return word;
    }

    /** 归一化匹配词面（仅自动机内部使用）。 */
    public String getMatchWord() {
        return matchWord;
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
