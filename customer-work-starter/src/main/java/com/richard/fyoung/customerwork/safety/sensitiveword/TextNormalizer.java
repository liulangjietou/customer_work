package com.richard.fyoung.customerwork.safety.sensitiveword;

import java.util.HashSet;
import java.util.Set;

/**
 * 文本归一化（纯函数，无状态、好单测）——敏感词"绕过对抗"的关键前处理。
 *
 * <p>三步归一，让 {@code 敏*感*词} / {@code 敏 感 词} / 全角 {@code ｍｉｎｇ} / 大小写变体都能落到同一词面：</p>
 * <ol>
 *   <li><b>全角转半角</b>：Unicode 全角区（U+FF01~U+FF5E）映回 ASCII，全角空格（U+3000）转普通空格；</li>
 *   <li><b>大小写归一</b>：统一转小写；</li>
 *   <li><b>去干扰字符</b>：剔除空白与常见插入符（{@code * . · • - _ ~ | / \} 及中文间隔号/顿号等），
 *       使插入式绕过失效。</li>
 * </ol>
 *
 * <p><b>繁转简：未做</b>——java8 标准库无内置繁简映射，引第三方库（如 opencc4j）会破坏"零新增依赖"约束，
 * 故此处跳过；如需可在下游用同签名的自定义 normalizer 叠加。</p>
 *
 * <p>{@link #normalizeTracked} 额外产出"归一化下标 → 原文下标"的映射，供打码时把命中片段还原到原文
 * （含被剔除的插入符）区间上，保证 {@code 敏*感*词} 也能整段打码。全角/小写均为 1:1 变换，剔除只减不增，
 * 故映射恒为单射、无需展开。</p>
 * @author owlzhangfq@gmail.com
 */
public final class TextNormalizer {

    /** 全角可视字符区间（对应 ASCII 0x21~0x7E）。 */
    private static final char FULLWIDTH_START = '！';
    private static final char FULLWIDTH_END = '～';
    private static final char FULLWIDTH_SPACE = '　';
    private static final int FULLWIDTH_TO_ASCII_OFFSET = 0xFEE0;

    /** 需剔除的常见插入干扰符（空白另行用 {@link Character#isWhitespace} 判定）。 */
    private static final Set<Character> NOISE_CHARS = buildNoiseChars();

    private TextNormalizer() {
    }

    /** 归一化并返回纯文本（构建词表 / 断言用；等价于 {@code normalizeTracked(raw).text()}）。 */
    public static String normalize(String raw) {
        return normalizeTracked(raw).text();
    }

    /**
     * 归一化并保留原文下标映射。
     *
     * @param raw 原始文本（null 视为空串）
     * @return 归一化文本 + {@code originalIndex[i]}=归一化第 i 个字符在原文中的下标
     */
    public static Normalized normalizeTracked(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Normalized("", new int[0]);
        }
        int len = raw.length();
        StringBuilder sb = new StringBuilder(len);
        int[] idx = new int[len];
        int kept = 0;
        for (int i = 0; i < len; i++) {
            char c = toHalfWidth(raw.charAt(i));
            if (isNoise(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
            idx[kept++] = i;
        }
        int[] originalIndex = new int[kept];
        System.arraycopy(idx, 0, originalIndex, 0, kept);
        return new Normalized(sb.toString(), originalIndex);
    }

    /** 全角字符转半角（非全角原样返回）。 */
    private static char toHalfWidth(char c) {
        if (c == FULLWIDTH_SPACE) {
            return ' ';
        }
        if (c >= FULLWIDTH_START && c <= FULLWIDTH_END) {
            return (char) (c - FULLWIDTH_TO_ASCII_OFFSET);
        }
        return c;
    }

    private static boolean isNoise(char c) {
        return Character.isWhitespace(c) || NOISE_CHARS.contains(c);
    }

    private static Set<Character> buildNoiseChars() {
        Set<Character> set = new HashSet<>();
        // ASCII 插入符
        for (char c : new char[]{'*', '.', '-', '_', '~', '|', '/', '\\', '^', '+', '='}) {
            set.add(c);
        }
        // 中文常见间隔/顿点符（全角转半角覆盖不到的 BMP 标点）
        for (char c : new char[]{'·', '•', '・', '。', '、', '‧', '･'}) {
            set.add(c);
        }
        return set;
    }

    /**
     * 归一化结果。
     *
     * @param text          归一化后的文本
     * @param originalIndex 归一化下标 → 原文下标的映射（长度与 {@code text} 一致）
     */
    public record Normalized(String text, int[] originalIndex) {
    }
}
