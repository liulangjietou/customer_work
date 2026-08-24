package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.core.model.attribution.ModelCallAttribution;
import com.richard.fyoung.customerwork.core.model.attribution.ModelPricingStatus;
import com.richard.fyoung.customerwork.core.model.experiment.OnlineExperimentAssignment;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallSegmentMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 调用日志存储测试（对接本机 MySQL；不可达自动跳过）：
 * 保存往返（主表 + 明细段）+ 条件分页 + 汇总 + 趋势 + 删除级联。
 * @author owlzhangfq@gmail.com
 */
class MybatisAgentCallLogStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private MybatisAgentCallLogStore store;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-calllog-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        SqlSessionTemplate template = MybatisTestSupport.template(dataSource);
        store = new MybatisAgentCallLogStore(
            template.getMapper(AgentCallLogMapper.class),
            template.getMapper(AgentCallSegmentMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private AgentCallRecord newRecord(String username, String agentCode, long startMs) {
        List<AgentCallSegment> segs = List.of(
            new AgentCallSegment(1, AgentCallKind.MODEL, "qwen-max", startMs, 30L, true, null,
                100L, 20L, 60L, 18L, new ModelCallAttribution("aliyun", 12L, "qwen-max",
                    91L, "CNY", new BigDecimal("2.50"), new BigDecimal("7.50"),
                    new BigDecimal("0.25"), ModelPricingStatus.PRICED)),
            new AgentCallSegment(2, AgentCallKind.TOOL, "queryOrder", startMs + 30, 20L, true, null, null, null, null, null),
            new AgentCallSegment(3, AgentCallKind.MCP, "mcp_weather", startMs + 50, 10L, false, "boom", null, null, null, null));
        return new AgentCallRecord(0L, "req-" + UUID.randomUUID(), "u-" + username, username, agentCode,
            "客服Agent", "sess-" + UUID.randomUUID(), AgentCallSessionType.CHAT, "问题", "回答",
            startMs, startMs + 60, 60L, 30L, 20L, 10L, 0L, segs.size(), 100L, 20L, 120L,
            60L, 18L, true, null,
            new OnlineExperimentAssignment(77L, 2, "TREATMENT", 12L, 4200),
            new AgentCallLineage("0123456789abcdef0123456789abcdef", "revision-7", "hash-7",
                new EvalVersionBinding("", "", "model-v1", "prompt-v1", "agent-v1",
                    "kb-v1", "tool-v1", "", "")),
            segs);
    }

    @Test
    void save_shouldRoundTripMainAndSegments() {
        AgentCallRecord saved = store.save(newRecord("alice-" + UUID.randomUUID(), "A", 1_600_000_000_000L));
        assertTrue(saved.id() > 0, "主键回填");

        List<AgentCallSegment> segs = store.findSegments(saved.id());
        assertEquals(3, segs.size());
        assertEquals(AgentCallKind.MODEL, segs.get(0).kind());
        assertEquals("qwen-max", segs.get(0).name());
        assertFalse(segs.get(2).success(), "MCP 段失败状态往返");
        assertEquals("boom", segs.get(2).errorMsg());
        // token 往返：MODEL 段有值，工具段为 null
        assertEquals(100L, segs.get(0).inputTokens(), "MODEL 段输入 token 往返");
        assertEquals(20L, segs.get(0).outputTokens(), "MODEL 段输出 token 往返");
        assertEquals("aliyun", segs.get(0).attribution().provider(), "真实供应商归因往返");
        assertEquals(12L, segs.get(0).attribution().deploymentId(), "真实部署归因往返");
        assertEquals(91L, segs.get(0).attribution().priceId(), "冻结价格版本往返");
        assertEquals(ModelPricingStatus.PRICED, segs.get(0).attribution().pricingStatus());
        assertEquals(new BigDecimal("0.00026500000000"), segs.get(0).cost().amount(),
            "分段成本可由持久化冻结价目精确复算");
        assertNull(segs.get(1).inputTokens(), "工具段无 token");

        AgentCallRecord roundTrip = store.findPage(new AgentCallLogQuery(
            saved.username(), null, null, null, null, null, 0, 10)).get(0);
        assertEquals("0123456789abcdef0123456789abcdef", roundTrip.lineage().traceId());
        assertEquals("revision-7", roundTrip.lineage().runtimeRevision());
        assertEquals("model-v1", roundTrip.lineage().versionBinding().modelVersion());
        assertEquals(77L, roundTrip.experimentAssignment().experimentId());
        assertEquals(2, roundTrip.experimentAssignment().revision());
        assertEquals("TREATMENT", roundTrip.experimentAssignment().arm());
        assertEquals(12L, roundTrip.experimentAssignment().deploymentId());
        assertEquals(4200, roundTrip.experimentAssignment().bucket());
    }

    @Test
    void findPageAndSummary_shouldFilterByUsername() {
        String user = "user-" + UUID.randomUUID();
        store.save(newRecord(user, "A", 1_600_000_000_000L));
        store.save(newRecord(user, "A", 1_600_000_100_000L));

        AgentCallLogQuery q = new AgentCallLogQuery(user, null, null, null, null, null, 0, 10);
        List<AgentCallRecord> page = store.findPage(q);
        assertEquals(2, page.size());
        assertTrue(page.get(0).id() > page.get(1).id(), "id 倒序");
        assertEquals(2, store.count(q));

        AgentCallLogSummary summary = store.summary(q);
        assertEquals(2, summary.totalCount());
        assertEquals(60d, summary.avgDurationMs(), 0.001);
        assertEquals(60L, summary.maxDurationMs());
        assertEquals(30d, summary.avgModelMs(), 0.001);
        // 每条 total_tokens=120，两条求和 240，均值 120
        assertEquals(240L, summary.totalTokens());
        assertEquals(120d, summary.avgTotalTokens(), 0.001);
    }

    @Test
    void trend_shouldAggregateByGranularity() {
        String user = "user-" + UUID.randomUUID();
        store.save(newRecord(user, "A", 1_600_000_000_000L));
        store.save(newRecord(user, "A", 1_600_000_050_000L));

        AgentCallLogQuery q = new AgentCallLogQuery(user, null, null, null, null, null, 0, 10);
        List<AgentCallTrendPoint> trend = store.trend(q, TrendGranularity.HOUR);
        assertFalse(trend.isEmpty());
        long total = trend.stream().mapToLong(AgentCallTrendPoint::count).sum();
        assertEquals(2, total);
    }

    @Test
    void delete_shouldCascadeSegments() {
        AgentCallRecord saved = store.save(newRecord("del-" + UUID.randomUUID(), "A", 1_600_000_000_000L));
        assertTrue(store.delete(saved.id()));
        assertTrue(store.findSegments(saved.id()).isEmpty(), "级联删除明细段");
        assertFalse(store.delete(saved.id()), "重复删除返回 false");
    }
}
