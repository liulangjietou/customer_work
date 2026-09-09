package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分策略的边界行为。
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentChunkerTest {

    @Test
    @DisplayName("短段落合并到上限，不会一段一片")
    void mergesShortParagraphs() {
        String content = String.join("\n\n", "第一条：甲。", "第二条：乙。", "第三条：丙。");

        List<String> chunks = DocumentChunker.chunk(content, 100, 20);

        assertEquals(1, chunks.size(), "三条短段落加起来没超上限，应合成一片");
        assertTrue(chunks.get(0).contains("甲") && chunks.get(0).contains("丙"));
    }

    @Test
    @DisplayName("超长段落切在句末，不把句子劈成两半")
    void splitsAtSentenceEnd() {
        String sentence = "这是一句用来占位的话。";
        String content = sentence.repeat(10);

        List<String> chunks = DocumentChunker.chunk(content, 40, 0);

        assertTrue(chunks.size() > 1, "内容远超上限，应被切开");
        for (String chunk : chunks.subList(0, chunks.size() - 1)) {
            assertTrue(chunk.endsWith("。"),
                "分片没有断在句末，切点落进了句子中间：「" + chunk + "」");
        }
    }

    /**
     * 整段没有任何标点时必须接受硬切。
     *
     * <p>一路往前找句末会把分片切得极短甚至退化成一字一片；找不到就硬切是刻意的保底。</p>
     */
    @Test
    @DisplayName("整段无标点时退回硬切，不会把分片切碎")
    void fallsBackToHardCutWithoutPunctuation() {
        String content = "甲".repeat(300);

        List<String> chunks = DocumentChunker.chunk(content, 100, 0);

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.length() == 100),
            "无标点文本应按上限均匀硬切，实际长度 "
                + chunks.stream().map(String::length).toList());
    }

    @Test
    @DisplayName("相邻分片带上重叠的上下文")
    void adjacentChunksOverlap() {
        String content = "句子甲。".repeat(50);

        List<String> withOverlap = DocumentChunker.chunk(content, 60, 20);

        assertTrue(withOverlap.size() >= 2);
        String first = withOverlap.get(0);
        String second = withOverlap.get(1);
        String tail = first.substring(Math.max(0, first.length() - 20));
        assertTrue(second.startsWith(tail.substring(0, Math.min(8, tail.length()))),
            "第二片没有带上第一片的结尾，重叠没生效：\n第一片尾=" + tail + "\n第二片头="
                + second.substring(0, Math.min(20, second.length())));
    }

    /**
     * 重叠不得导致原地打转。
     *
     * <p>{@code 下一片起点 = 本片终点 - overlap}，若 overlap 大到把起点推回本片起点之前，
     * 同一段文本会被无限切下去。实现里用 {@code max(next, start + 1)} 兜底，这里钉住它。</p>
     */
    @Test
    @DisplayName("重叠配得过大也不会无限切分")
    void oversizedOverlapTerminates() {
        String content = "甲".repeat(500);

        List<String> chunks = DocumentChunker.chunk(content, 100, 10_000);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() < 200,
            "分片数异常膨胀（" + chunks.size() + "），重叠回退可能没有收敛");
    }

    @Test
    @DisplayName("空白内容退回原文，文档不会在知识库里消失")
    void blankContentFallsBackToOriginal() {
        assertEquals(List.of("   "), DocumentChunker.chunk("   ", 100, 20));
    }

    @Test
    @DisplayName("overlap 为 0 时行为与不重叠一致")
    void zeroOverlapMeansNoOverlap() {
        String content = "句子甲。".repeat(50);

        List<String> chunks = DocumentChunker.chunk(content, 60, 0);

        int total = chunks.stream().mapToInt(String::length).sum();
        assertEquals(content.length(), total,
            "不重叠时各分片长度之和应等于原文长度，实际 " + total);
    }
}
