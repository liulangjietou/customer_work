package com.richard.fyoung.customerwork.core.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源标记的生成与解析是同一份格式的两端，必须真的做一次往返。
 *
 * <p>只断言"生成的字符串长这样"照不出漂移：改了生成端而忘了解析端，那种断言照样绿，
 * 而线上表现是引用恒为空——检索在工作、标记也写进去了，就是没人认领。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeCitationMarkerTest {

    @Test
    @DisplayName("生成的标记能被原样解析回来")
    void markerRoundTrips() {
        KnowledgeCitation original = new KnowledgeCitation("售后FAQ", "doc-refund-policy", "10237", 0.87);

        List<KnowledgeCitation> parsed = KnowledgeCitation.parseAll(original.marker());

        assertEquals(1, parsed.size());
        assertEquals(original.knowledgeBase(), parsed.get(0).knowledgeBase());
        assertEquals(original.documentId(), parsed.get(0).documentId());
        assertEquals(original.chunkId(), parsed.get(0).chunkId());
        assertEquals(0.87, parsed.get(0).score(), 0.0001);
    }

    @Test
    @DisplayName("知识库名里的竖线不会撑破分隔符")
    void pipeInFieldDoesNotBreakParsing() {
        KnowledgeCitation original = new KnowledgeCitation("售后 | 退换货", "doc-a", "77", 0.5);

        List<KnowledgeCitation> parsed = KnowledgeCitation.parseAll(original.marker());

        assertEquals(1, parsed.size(), "字段里的竖线把标记切成了更多段");
        assertEquals("doc-a", parsed.get(0).documentId(), "文档标识错位了");
        assertEquals("77", parsed.get(0).chunkId());
    }

    @Test
    @DisplayName("从混着正文的多段文本里解析出全部引用")
    void parsesFromMixedText() {
        String toolResult = "检索到 2 个结果：\n"
            + new KnowledgeCitation("售后FAQ", "doc-a", "1", 0.9).marker() + "\n"
            + "七天无理由退货适用于未拆封商品。\n"
            + new KnowledgeCitation("物流政策", "doc-b", "2", 0.6).marker() + "\n"
            + "偏远地区配送时效为 5-7 天。\n";

        List<KnowledgeCitation> parsed = KnowledgeCitation.parseAll(toolResult);

        assertEquals(List.of("1", "2"), parsed.stream().map(KnowledgeCitation::chunkId).toList());
        assertEquals("物流政策", parsed.get(1).knowledgeBase());
    }

    @Test
    @DisplayName("标记被包在其它内容里（如注入护栏的隔离标签）仍能认出")
    void parsesWhenWrappedByOtherContent() {
        String wrapped = "<untrusted-abc>\n"
            + new KnowledgeCitation("售后FAQ", "doc-a", "5", 0.3).marker() + "\n"
            + "正文\n</untrusted-abc>";

        assertEquals(1, KnowledgeCitation.parseAll(wrapped).size());
    }

    @Test
    @DisplayName("残缺或无关的行一律跳过，不抛异常")
    void malformedLinesAreSkipped() {
        assertTrue(KnowledgeCitation.parseAll("普通的一段回答，没有任何标记").isEmpty());
        assertTrue(KnowledgeCitation.parseAll("【知识来源】只有一个字段").isEmpty());
        assertTrue(KnowledgeCitation.parseAll("【知识来源】库 | 文档 | #").isEmpty(), "分片标识为空的不该被收下");
        assertTrue(KnowledgeCitation.parseAll(null).isEmpty());
        assertTrue(KnowledgeCitation.parseAll("").isEmpty());
    }

    @Test
    @DisplayName("分数缺失或不是数字时引用本身仍然可用")
    void scoreIsOptional() {
        List<KnowledgeCitation> noScore = KnowledgeCitation.parseAll("【知识来源】库 | 文档 | #9");
        assertEquals(1, noScore.size());
        assertNull(noScore.get(0).score());

        List<KnowledgeCitation> badScore = KnowledgeCitation.parseAll("【知识来源】库 | 文档 | #9 | 高");
        assertEquals(1, badScore.size(), "分数解析失败不该让整条引用丢掉");
        assertNull(badScore.get(0).score());
        assertEquals("9", badScore.get(0).chunkId());
    }

    @Test
    @DisplayName("分数为 null 时标记不写分数段，且能解析回 null")
    void nullScoreOmitsSegment() {
        KnowledgeCitation original = new KnowledgeCitation("库", "文档", "3", null);

        String marker = original.marker();
        List<KnowledgeCitation> parsed = KnowledgeCitation.parseAll(marker);

        assertEquals(1, parsed.size());
        assertNull(parsed.get(0).score());
        assertEquals("3", parsed.get(0).chunkId());
    }
}
