package com.richard.fyoung.customerwork.tool.devtool;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则匹配测试纯函数集。
 *
 * <p>返回匹配总数与每个匹配的位置/内容/分组，最多返回 {@value #MAX_MATCHES} 个匹配，超出截断并标注。
 * 无 Spring 依赖、无状态，参数非法入口 fast-fail。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class RegexDevToolOps {

    /** 最多返回的匹配数（超出仅计数、不再收集明细）。 */
    private static final int MAX_MATCHES = 100;

    /**
     * 正则测试。
     *
     * @param pattern 正则表达式
     * @param text    待匹配文本（允许空串）
     * @param flags   标志位，任意组合 i(忽略大小写)/m(多行)/s(dotall)，可空
     * @return 匹配结果
     */
    public RegexResult test(String pattern, String text, String flags) {
        DevToolArgs.requireNonBlank(pattern, "pattern");
        DevToolArgs.requireNonNull(text, "text");
        Pattern compiled = compile(pattern, parseFlags(flags));

        Matcher matcher = compiled.matcher(text);
        List<RegexMatch> matches = new ArrayList<>();
        int count = 0;
        boolean truncated = false;
        while (matcher.find()) {
            count++;
            if (matches.size() < MAX_MATCHES) {
                List<String> groups = new ArrayList<>();
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    groups.add(matcher.group(i));
                }
                matches.add(RegexMatch.builder()
                    .start(matcher.start())
                    .end(matcher.end())
                    .value(matcher.group())
                    .groups(groups)
                    .build());
            } else {
                truncated = true;
            }
        }
        return RegexResult.builder()
            .matchCount(count)
            .truncated(truncated)
            .matches(matches)
            .build();
    }

    /** 编译正则，非法语法抛出带说明的异常。 */
    private Pattern compile(String pattern, int flagBits) {
        try {
            return Pattern.compile(pattern, flagBits);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("非法正则表达式：" + e.getMessage(), e);
        }
    }

    /** 解析 flags 字符串为 Pattern 标志位。 */
    private int parseFlags(String flags) {
        if (flags == null || flags.isEmpty()) {
            return 0;
        }
        int bits = 0;
        for (char c : flags.toCharArray()) {
            switch (Character.toLowerCase(c)) {
                case 'i':
                    bits |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    break;
                case 'm':
                    bits |= Pattern.MULTILINE;
                    break;
                case 's':
                    bits |= Pattern.DOTALL;
                    break;
                default:
                    throw new IllegalArgumentException("不支持的正则 flag：'" + c + "'，仅支持 i/m/s");
            }
        }
        return bits;
    }

    /**
     * 正则测试结果。
     */
    @Getter
    @Builder
    public static class RegexResult {
        /** 匹配总数（含被截断未收集的部分）。 */
        private final int matchCount;
        /** 是否因超过上限而截断明细。 */
        private final boolean truncated;
        /** 匹配明细（最多 100 条）。 */
        private final List<RegexMatch> matches;
    }

    /**
     * 单个匹配明细。
     */
    @Getter
    @Builder
    public static class RegexMatch {
        /** 起始下标（含）。 */
        private final int start;
        /** 结束下标（不含）。 */
        private final int end;
        /** 匹配到的完整文本。 */
        private final String value;
        /** 捕获分组（按分组序，可能含 null 表示未参与匹配的可选分组）。 */
        private final List<String> groups;
    }
}
