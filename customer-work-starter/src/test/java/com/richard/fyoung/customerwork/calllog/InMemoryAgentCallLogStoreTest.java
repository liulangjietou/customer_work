package com.richard.fyoung.customerwork.calllog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存调用日志存储单测：保存回填主键 + 条件分页 + 明细段 + 删除 + 汇总 + 趋势聚合。
 * @author owlzhangfq@gmail.com
 */
class InMemoryAgentCallLogStoreTest {

    private InMemoryAgentCallLogStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryAgentCallLogStore();
    }

    /** 每条固定 input=10/output=5/total=15，供 token 汇总/趋势断言确定性。 */
    private AgentCallRecord record(String username, String agentCode, long startMs, long durationMs,
                                   long modelMs, long toolMs, List<AgentCallSegment> segments) {
        return new AgentCallRecord(0L, "req-" + startMs, "u-" + username, username, agentCode,
            "客服Agent", "sess-1", AgentCallSessionType.CHAT, "问题", "回答",
            startMs, startMs + durationMs, durationMs, modelMs, toolMs, 0L, 0L,
            segments.size(), 10L, 5L, 15L, 4L, 8L, true, null, segments);
    }

    @Test
    void save_shouldAssignAutoIncrementId() {
        AgentCallRecord saved = store.save(record("alice", "A", 1000L, 50L, 30L, 20L, List.of()));
        assertTrue(saved.id() > 0, "自增主键回填");
    }

    @Test
    void findPage_shouldFilterAndOrderByIdDesc() {
        store.save(record("alice", "A", 1000L, 10L, 5L, 5L, List.of()));
        store.save(record("bob", "A", 2000L, 20L, 10L, 10L, List.of()));
        store.save(record("alice", "B", 3000L, 30L, 15L, 15L, List.of()));

        List<AgentCallRecord> byUser = store.findPage(new AgentCallLogQuery(
            "alice", null, null, null, null, null, 0, 10));
        assertEquals(2, byUser.size());
        assertTrue(byUser.get(0).id() > byUser.get(1).id(), "id 倒序（最新在前）");

        List<AgentCallRecord> byAgent = store.findPage(new AgentCallLogQuery(
            null, "A", null, null, null, null, 0, 10));
        assertEquals(2, byAgent.size());

        assertEquals(3, store.count(AgentCallLogQuery.page(0, 100)));
    }

    @Test
    void findPage_shouldFilterByTimeRange() {
        store.save(record("alice", "A", 1000L, 10L, 5L, 5L, List.of()));
        store.save(record("alice", "A", 5000L, 10L, 5L, 5L, List.of()));

        List<AgentCallRecord> ranged = store.findPage(new AgentCallLogQuery(
            null, null, null, null, 2000L, 9000L, 0, 10));
        assertEquals(1, ranged.size());
        assertEquals(5000L, ranged.get(0).startTimeMs());
    }

    @Test
    void findSegmentsAndDelete_shouldWork() {
        List<AgentCallSegment> segs = List.of(
            new AgentCallSegment(1, AgentCallKind.MODEL, "qwen-max", 1000L, 30L, true, null, 100L, 20L, 60L, 18L),
            new AgentCallSegment(2, AgentCallKind.TOOL, "queryOrder", 1030L, 20L, true, null, null, null, null, null));
        AgentCallRecord saved = store.save(record("alice", "A", 1000L, 50L, 30L, 20L, segs));

        List<AgentCallSegment> loaded = store.findSegments(saved.id());
        assertEquals(2, loaded.size());
        assertEquals(1, loaded.get(0).seq());
        assertEquals(100L, loaded.get(0).inputTokens(), "MODEL 段输入 token");
        assertNull(loaded.get(1).inputTokens(), "工具段无 token");

        assertTrue(store.delete(saved.id()));
        assertFalse(store.delete(saved.id()), "重复删除返回 false");
        assertTrue(store.findSegments(saved.id()).isEmpty());
    }

    @Test
    void summary_shouldAggregate() {
        store.save(record("alice", "A", 1000L, 100L, 60L, 40L, List.of()));
        store.save(record("alice", "A", 2000L, 200L, 120L, 80L, List.of()));

        AgentCallLogSummary s = store.summary(new AgentCallLogQuery(
            "alice", null, null, null, null, null, 0, 0));
        assertEquals(2, s.totalCount());
        assertEquals(150d, s.avgDurationMs(), 0.001);
        assertEquals(200L, s.maxDurationMs());
        assertEquals(90d, s.avgModelMs(), 0.001);
        assertEquals(60d, s.avgToolMs(), 0.001);
        // 每条 total=15，两条求和 30，均值 15
        assertEquals(30L, s.totalTokens());
        assertEquals(15d, s.avgTotalTokens(), 0.001);
    }

    @Test
    void summary_empty_shouldReturnZero() {
        AgentCallLogSummary s = store.summary(AgentCallLogQuery.page(0, 0));
        assertEquals(0L, s.totalCount());
        assertEquals(0d, s.avgDurationMs(), 0.001);
    }

    @Test
    void trend_byDay_shouldBucketAndOrder() {
        // 2021-01-01 与 2021-01-02（用固定毫秒时间戳，按系统时区分桶，只验证分桶数与计数）
        long day1 = 1609459200000L; // 2021-01-01T00:00:00Z 附近
        long day2 = day1 + 86_400_000L * 1;
        store.save(record("alice", "A", day1, 100L, 0L, 0L, List.of()));
        store.save(record("alice", "A", day1 + 3600_000L, 300L, 0L, 0L, List.of()));
        store.save(record("alice", "A", day2, 200L, 0L, 0L, List.of()));

        List<AgentCallTrendPoint> trend = store.trend(AgentCallLogQuery.page(0, 0), TrendGranularity.DAY);
        assertTrue(trend.size() >= 1, "应至少产出一个时间桶");
        long total = trend.stream().mapToLong(AgentCallTrendPoint::count).sum();
        assertEquals(3, total, "全部三条计入趋势");
        // 每条 total=15，三条求和 45（跨桶合计）
        assertEquals(45L, trend.stream().mapToLong(AgentCallTrendPoint::totalTokens).sum());
        // 桶标签升序
        for (int i = 1; i < trend.size(); i++) {
            assertTrue(trend.get(i - 1).bucket().compareTo(trend.get(i).bucket()) <= 0);
        }
    }
}
