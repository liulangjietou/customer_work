package com.richard.fyoung.customerwork.safety.correction;

import java.util.List;

/**
 * 流式文本上的关键词匹配：逐片喂入，跨片也能命中。
 *
 * <h3>为什么不能逐片直接 contains</h3>
 * <p>模型按 token 吐字，「已退款」三个字常常分在两三片里到达。逐片匹配的结果是
 * <b>永远匹配不上</b>——而且不报任何错，看起来只是"这次没命中"。
 * 本类维护一个不超过「最长关键词长度 - 1」的尾部缓冲：每片到达后与缓冲拼接再匹配，
 * 放行确定不可能再构成关键词的前半段，尾部留着等下一片。</p>
 *
 * <p><b>只做匹配，不做处置</b>：命中之后是拦、是改写还是转人工，属于业务判断，
 * 由调用方决定。这样这段容易写错的缓冲逻辑可以被单独测透。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class StreamKeywordMatcher {

    private final List<String> keywords;
    private final int maxKeywordLength;

    private final StringBuilder buffer = new StringBuilder();
    private boolean matched;
    private String matchedKeyword;

    public StreamKeywordMatcher(List<String> keywords) {
        this.keywords = keywords == null ? List.of() : keywords.stream()
            .filter(k -> k != null && !k.isBlank())
            .toList();
        this.maxKeywordLength = this.keywords.stream().mapToInt(String::length).max().orElse(0);
    }

    /**
     * 喂入一个增量片段。
     *
     * @return 本次可以安全放行的文本（可能为空串——尾部还不能确定时会被留下）
     */
    public String accept(String delta) {
        if (delta == null || delta.isEmpty() || keywords.isEmpty()) {
            return delta == null ? "" : delta;
        }
        if (matched) {
            // 已经命中过：后续内容一律不再放行，由调用方决定怎么收尾
            return "";
        }
        buffer.append(delta);
        String window = buffer.toString();
        for (String keyword : keywords) {
            int at = window.indexOf(keyword);
            if (at >= 0) {
                matched = true;
                matchedKeyword = keyword;
                // 只放行命中点之前的部分，关键词本身及其后的内容全部扣下——
                // 否则"已退款"三个字仍会出现在用户屏幕上，检测等于白做
                buffer.setLength(0);
                return window.substring(0, at);
            }
        }
        // 留下可能成为某个关键词前缀的尾巴；其余放行。
        // 保留 maxKeywordLength - 1 个字符即可：任何关键词要跨片匹配，
        // 最多只需要它自己的长度减一个字符留在缓冲里
        int keep = Math.min(window.length(), Math.max(0, maxKeywordLength - 1));
        String emit = window.substring(0, window.length() - keep);
        buffer.setLength(0);
        buffer.append(window, window.length() - keep, window.length());
        return emit;
    }

    /**
     * 流结束：把缓冲区里剩的尾巴交出来。不调用它会吞掉正文最后几个字。
     *
     * <p>命中之后缓冲已被清空，因此这里返回空串——不会把扣下的内容又漏出去。</p>
     */
    public String flush() {
        String rest = buffer.toString();
        buffer.setLength(0);
        return rest;
    }

    public boolean isMatched() {
        return matched;
    }

    /** 命中的那个关键词，供日志与审计定位；未命中返回 {@code null}。 */
    public String matchedKeyword() {
        return matchedKeyword;
    }
}
