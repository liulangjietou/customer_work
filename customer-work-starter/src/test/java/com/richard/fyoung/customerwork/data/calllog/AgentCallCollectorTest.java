package com.richard.fyoung.customerwork.data.calllog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集器单测：分段序号自增、四类耗时汇总、答案采集、成败与错误信息组装。
 * @author owlzhangfq@gmail.com
 */
class AgentCallCollectorTest {

    @Test
    void toRecord_shouldAggregateSegmentsByKind() {
        AgentCallCollector collector = new AgentCallCollector();
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();
        collector.addSegment(AgentCallKind.MODEL, "qwen-max", now, nano, true, null, 120L, 30L, 80L, 25L);
        collector.addSegment(AgentCallKind.TOOL, "queryOrder", now, nano, true, null, null, null, null, null);
        collector.addSegment(AgentCallKind.MCP, "mcp_weather", now, nano, true, null, null, null, null, null);
        collector.addSegment(AgentCallKind.SKILL, "skill_pdf", now, nano, false, "boom", null, null, null, null);
        collector.setAnswer("最终回答");

        AgentCallRecord record = collector.toRecord("req-1", "tenantA", "userA", "agentA",
            "客服Agent", "sess-1", AgentCallSessionType.CHAT, "你好", now + 100, true, null);

        assertEquals(4, record.segmentCount(), "分段数");
        assertEquals(4, record.segments().size());
        assertEquals("最终回答", record.answer());
        assertEquals("req-1", record.requestId());
        assertEquals(AgentCallSessionType.CHAT, record.sessionType());
        assertTrue(record.success());
        // token 汇总：仅 MODEL 段有 usage（120/30），其余段 null 不计入
        assertEquals(120L, record.inputTokens(), "请求级输入 token = MODEL 段之和");
        assertEquals(30L, record.outputTokens(), "请求级输出 token = MODEL 段之和");
        assertEquals(150L, record.totalTokens(), "请求级总 token = 输入 + 输出");
        assertEquals(80L, record.cachedTokens(), "请求级缓存 token = MODEL 段之和");
        assertEquals(25L, record.modelReportedMs(), "模型自报耗时 = MODEL 段之和");
        assertEquals(120L, record.segments().get(0).inputTokens(), "MODEL 段输入 token");
        assertEquals(30L, record.segments().get(0).outputTokens(), "MODEL 段输出 token");
        assertEquals(80L, record.segments().get(0).cachedTokens(), "MODEL 段缓存 token");
        assertNull(record.segments().get(1).inputTokens(), "工具段无 token");
        assertNull(record.segments().get(1).cachedTokens(), "工具段无缓存 token");
        // 每类各一段，各段耗时 >=0，四类互不串味
        assertTrue(record.modelMs() >= 0 && record.toolMs() >= 0
            && record.mcpMs() >= 0 && record.skillMs() >= 0);
        // 分段序号从 1 起自增
        assertEquals(1, record.segments().get(0).seq());
        assertEquals(4, record.segments().get(3).seq());
        assertEquals(AgentCallKind.SKILL, record.segments().get(3).kind());
        assertFalse(record.segments().get(3).success());
        assertEquals("boom", record.segments().get(3).errorMsg());
    }

    @Test
    void toRecord_emptySegments_shouldZeroAggregates() {
        AgentCallCollector collector = new AgentCallCollector();
        AgentCallRecord record = collector.toRecord("req-2", "u", "u", "a", "a", "s",
            AgentCallSessionType.VIBE_CODING, "q", System.currentTimeMillis(), false, "failed");

        assertEquals(0, record.segmentCount());
        assertEquals(0L, record.modelMs());
        assertEquals(0L, record.toolMs());
        assertEquals(0L, record.mcpMs());
        assertEquals(0L, record.skillMs());
        // 无分段 → 无 usage：三个 token 字段均 null（区分"未采到"与"用了 0 token"）
        assertNull(record.inputTokens());
        assertNull(record.outputTokens());
        assertNull(record.totalTokens());
        assertNull(record.answer());
        assertFalse(record.success());
        assertEquals("failed", record.errorMsg());
        assertEquals(AgentCallSessionType.VIBE_CODING, record.sessionType());
    }
}
