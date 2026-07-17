package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.observability.mapper.AuditLogMapper;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MybatisAuditSink 查询侧测试（对接本机 MySQL，见 customer-work-schema.sql 的 cw_audit_log 表）。
 *
 * <p>MySQL 不可达时自动跳过；在线时真实写入若干审计记录后按 sessionId 检索，验证
 * {@link MybatisAuditSink#queryBySession} 能按 {@code agent_name=CustomerServiceAgent-<sessionId>} 后缀匹配。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisAuditSinkQueryTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private MybatisAuditSink store;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-audit-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        store = new MybatisAuditSink(MybatisTestSupport.mapper(dataSource, AuditLogMapper.class));
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

    @Test
    void queryBySession_shouldReturnRecordsForThatSession() {
        String sessionId = "tenantIT:conv-" + UUID.randomUUID();
        String agent = "CustomerServiceAgent-" + sessionId;

        store.record("tool-call", Map.of("agent", agent, "tool", "queryOrder"));
        store.record("final-answer", Map.of("agent", agent, "answer", "done"));
        // 另一个会话，不应被查出
        store.record("tool-call", Map.of("agent", "CustomerServiceAgent-other:conv-x", "tool", "x"));

        List<AuditRecord> records = store.queryBySession(sessionId, 20);
        assertFalse(records.isEmpty(), "应查到本会话审计记录");
        assertTrue(records.stream().allMatch(r -> r.agentName().endsWith(sessionId)),
            "查询结果应全部属于目标会话");
        assertTrue(records.stream().anyMatch(r -> "tool-call".equals(r.eventType())));
    }

    @Test
    void queryBySession_shouldReturnEmptyForUnknownSession() {
        List<AuditRecord> records = store.queryBySession("no-such:conv-" + UUID.randomUUID(), 20);
        assertTrue(records.isEmpty());
    }
}
