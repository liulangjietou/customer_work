package com.richard.fyoung.customerwork.slotfilling;

import lombok.Getter;

import java.util.regex.Pattern;

/**
 * 槽位定义（多轮信息收集，借鉴阿里商旅 AliGo「事项收集智能体」）。
 *
 * <p>有 {@code pattern} 的槽位可从任意一句用户输入里正则抽取（如订单号）；无 {@code pattern} 的
 * 自由文本槽位（如退款原因）只在"轮到追问它"时把整句作为取值。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class Slot {

    private final String name;
    private final String displayName;
    /** 该槽位缺失时对用户的追问语。 */
    private final String askPrompt;
    private final boolean required;
    /** 可空：非空则用于从用户文本中正则抽取该槽位值。 */
    private final Pattern pattern;

    public Slot(String name, String displayName, String askPrompt, boolean required, Pattern pattern) {
        this.name = name;
        this.displayName = displayName;
        this.askPrompt = askPrompt;
        this.required = required;
        this.pattern = pattern;
    }

    /** 有正则抽取规则的槽位。 */
    public static Slot withPattern(String name, String displayName, String askPrompt, String regex) {
        return new Slot(name, displayName, askPrompt, true, Pattern.compile(regex));
    }

    /** 自由文本槽位（追问轮整句取值）。 */
    public static Slot freeText(String name, String displayName, String askPrompt) {
        return new Slot(name, displayName, askPrompt, true, null);
    }

    /** 尝试从用户文本抽取本槽位值；无 pattern 或未命中返回 null。 */
    public String extract(String userText) {
        if (pattern == null || userText == null) {
            return null;
        }
        java.util.regex.Matcher m = pattern.matcher(userText);
        return m.find() ? m.group() : null;
    }
}
