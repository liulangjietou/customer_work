package com.richard.fyoung.customerwork.calllog;

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
        collector.addSegment(AgentCallKind.MODEL, "qwen-max", now, nano, true, null);
        collector.addSegment(AgentCallKind.TOOL, "queryOrder", now, nano, true, null);
        collector.addSegment(AgentCallKind.MCP, "mcp_weather", now, nano, true, null);
        collector.addSegment(AgentCallKind.SKILL, "skill_pdf", now, nano, false, "boom");
        collector.setAnswer("最终回答");

        AgentCallRecord record = collector.toRecord("req-1", "tenantA", "userA", "agentA",
            "客服Agent", "sess-1", AgentCallSessionType.CHAT, "你好", now + 100, true, null);

        assertEquals(4, record.segmentCount(), "分段数");
        assertEquals(4, record.segments().size());
        assertEquals("最终回答", record.answer());
        assertEquals("req-1", record.requestId());
        assertEquals(AgentCallSessionType.CHAT, record.sessionType());
        assertTrue(record.success());
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
        assertNull(record.answer());
        assertFalse(record.success());
        assertEquals("failed", record.errorMsg());
        assertEquals(AgentCallSessionType.VIBE_CODING, record.sessionType());
    }
}
