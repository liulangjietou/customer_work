package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 文本归一化单测：全角转半角、大小写归一、去干扰符、下标映射。
 * @author owlzhangfq@gmail.com
 */
class TextNormalizerTest {

    @Test
    void fullWidth_shouldBecomeHalfWidthAndLowercase() {
        // 全角字母 ＡＢＣ -> abc
        assertEquals("abc", TextNormalizer.normalize("ＡＢＣ"));
        assertEquals("123", TextNormalizer.normalize("１２３"));
    }

    @Test
    void caseAndWhitespace_shouldBeNormalized() {
        assertEquals("abc", TextNormalizer.normalize("A b C"));
        assertEquals("abc", TextNormalizer.normalize("Ab\tC\n"));
        // 全角空格
        assertEquals("abc", TextNormalizer.normalize("A　b　C"));
    }

    @Test
    void noiseChars_shouldBeStripped() {
        assertEquals("abc", TextNormalizer.normalize("a*b.c"));
        assertEquals("abc", TextNormalizer.normalize("a_b-c"));
        assertEquals("abc", TextNormalizer.normalize("a·b•c"));
        assertEquals("mingan", TextNormalizer.normalize("m|i/n\\g^a+n"));
    }

    @Test
    void chineseInsertion_shouldStillCollapse() {
        assertEquals("测试敏感词a", TextNormalizer.normalize("测*试*敏*感*词*A"));
        assertEquals("测试敏感词a", TextNormalizer.normalize("测 试 敏 感 词 A"));
    }

    @Test
    void normalizeTracked_shouldMapBackToOriginalIndex() {
        TextNormalizer.Normalized n = TextNormalizer.normalizeTracked("a*b");
        assertEquals("ab", n.text());
        // 归一化第 0 个字符 'a' 在原文下标 0；第 1 个 'b' 在原文下标 2（* 被剔除）
        assertArrayEquals(new int[]{0, 2}, n.originalIndex());
    }

    @Test
    void nullOrEmpty_shouldReturnEmpty() {
        assertEquals("", TextNormalizer.normalize(null));
        assertEquals("", TextNormalizer.normalize(""));
        assertEquals(0, TextNormalizer.normalizeTracked(null).originalIndex().length);
    }
}
