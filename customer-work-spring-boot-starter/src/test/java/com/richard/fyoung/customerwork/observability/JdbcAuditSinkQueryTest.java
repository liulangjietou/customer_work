package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JdbcAuditSink 查询侧测试（对接本机 MySQL，见 mysql/schema.sql 的 cw_audit_log 表）。
 *
 * <p>MySQL 不可达时自动跳过；在线时真实写入若干审计记录后按 sessionId 检索，验证
 * {@link JdbcAuditSink#queryBySession} 能按 {@code agent_name=CustomerServiceAgent-<sessionId>} 后缀匹配。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcAuditSinkQueryTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private JdbcAuditSink sink;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setHost(HOST);
        cfg.setPort(PORT);
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");
        sink = new JdbcAuditSink(buildDataSource(cfg));
    }

    private static DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(2);
        ds.setPoolName("cw-audit-query-test-pool");
        return ds;
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

        sink.record("tool-call", Map.of("agent", agent, "tool", "queryOrder"));
        sink.record("final-answer", Map.of("agent", agent, "answer", "done"));
        // 另一个会话，不应被查出
        sink.record("tool-call", Map.of("agent", "CustomerServiceAgent-other:conv-x", "tool", "x"));

        List<AuditRecord> records = sink.queryBySession(sessionId, 20);
        assertFalse(records.isEmpty(), "应查到本会话审计记录");
        assertTrue(records.stream().allMatch(r -> r.agentName().endsWith(sessionId)),
            "查询结果应全部属于目标会话");
        assertTrue(records.stream().anyMatch(r -> "tool-call".equals(r.eventType())));
    }

    @Test
    void queryBySession_shouldReturnEmptyForUnknownSession() {
        List<AuditRecord> records = sink.queryBySession("no-such:conv-" + UUID.randomUUID(), 20);
        assertTrue(records.isEmpty());
    }
}
