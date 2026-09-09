package com.richard.fyoung.customerwork.safety.correction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式关键词匹配：跨片命中是这里唯一难写对的地方。
 *
 * @author owlzhangfq@gmail.com
 */
class StreamKeywordMatcherTest {

    private static final List<String> KEYWORDS = List.of("已退款", "款项已退", "已到账");

    /**
     * 模型按 token 吐字，「已退款」常被拆在两三片里。
     *
     * <p>逐片直接 contains 的结果是<b>永远匹配不上</b>，而且不报错，
     * 看起来只是"这次没命中"——这条测试就是为了钉住它。</p>
     */
    @Test
    @DisplayName("关键词被拆在多个分片里仍能命中")
    void matchesAcrossChunks() {
        StreamKeywordMatcher matcher = new StreamKeywordMatcher(KEYWORDS);

        matcher.accept("您的订单");
        matcher.accept("已");
        matcher.accept("退");
        assertFalse(matcher.isMatched(), "「已退」还不构成关键词");
        matcher.accept("款");

        assertTrue(matcher.isMatched());
        assertEquals("已退款", matcher.matchedKeyword());
    }

    @Test
    @DisplayName("命中时只放行命中点之前的文本，关键词本身不会漏到用户屏幕上")
    void emitsNothingFromMatchOnward() {
        StreamKeywordMatcher matcher = new StreamKeywordMatcher(KEYWORDS);

        StringBuilder emitted = new StringBuilder();
        emitted.append(matcher.accept("您的订单"));
        emitted.append(matcher.accept("已退款，请注意查收"));
        emitted.append(matcher.flush());

        assertTrue(matcher.isMatched());
        assertFalse(emitted.toString().contains("已退款"),
            "关键词漏进了放行文本，检测等于白做：" + emitted);
        assertEquals("您的订单", emitted.toString());
    }

    @Test
    @DisplayName("命中后不再放行任何后续内容")
    void stopsEmittingAfterMatch() {
        StreamKeywordMatcher matcher = new StreamKeywordMatcher(KEYWORDS);
        matcher.accept("已退款");

        assertEquals("", matcher.accept("后面还有很多字"));
        assertEquals("", matcher.flush());
    }

    @Test
    @DisplayName("未命中时所有文本最终都能完整放行，一个字不少")
    void emitsEverythingWhenNoMatch() {
        StreamKeywordMatcher matcher = new StreamKeywordMatcher(KEYWORDS);
        String text = "您的退货申请已提交，预计三个工作日内处理完成。";

        StringBuilder emitted = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            emitted.append(matcher.accept(String.valueOf(text.charAt(i))));
        }
        emitted.append(matcher.flush());

        assertFalse(matcher.isMatched());
        assertEquals(text, emitted.toString(), "逐字喂入后有内容被吞掉了");
    }

    @Test
    @DisplayName("没有关键词时完全透传")
    void passesThroughWithoutKeywords() {
        StreamKeywordMatcher matcher = new StreamKeywordMatcher(List.of());

        assertEquals("原样输出", matcher.accept("原样输出"));
        assertFalse(matcher.isMatched());
    }

    @Test
    @DisplayName("空片段与 null 不会打乱缓冲")
    void handlesEmptyInput() {
        StreamKeywordMatcher matcher = new StreamKeywordMatcher(KEYWORDS);

        assertEquals("", matcher.accept(null));
        assertEquals("", matcher.accept(""));
        matcher.accept("已退");
        assertEquals("", matcher.accept(""));
        matcher.accept("款");
        assertTrue(matcher.isMatched(), "空片段插在中间不应打断跨片匹配");
    }

    @Test
    @DisplayName("较长的关键词也能跨片命中（缓冲长度按最长关键词算）")
    void respectsLongestKeyword() {
        StreamKeywordMatcher matcher = new StreamKeywordMatcher(KEYWORDS);

        for (String piece : List.of("您的", "款", "项", "已", "退")) {
            matcher.accept(piece);
        }

        assertTrue(matcher.isMatched());
        assertEquals("款项已退", matcher.matchedKeyword());
    }
}
