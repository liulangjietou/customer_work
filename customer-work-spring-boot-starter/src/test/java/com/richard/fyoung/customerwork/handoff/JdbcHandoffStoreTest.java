package com.richard.fyoung.customerwork.handoff;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 人机切换工单存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，见 mysql/schema.sql 中的 cw_handoff_ticket 表）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue），保证 {@code mvn test} 在无 MySQL 的环境仍然通过；
 * MySQL 在线的机器上真实执行存-取-改往返，验证 {@link JdbcHandoffStore}（含自动建表）。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcHandoffStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private JdbcHandoffStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setHost(HOST);
        cfg.setPort(PORT);
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");

        DataSource dataSource = new HandoffConfig().buildDataSource(cfg);
        store = new JdbcHandoffStore(dataSource);
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
    void saveAndFind_shouldRoundTrip() {
        String id = "HO-jdbc-it-" + UUID.randomUUID();
        HandoffTicket ticket = new HandoffTicket(id, "s1", "test", System.currentTimeMillis());
        store.save(ticket);

        HandoffTicket found = store.find(id).orElseThrow();
        assertEquals(id, found.getId());
        assertEquals("s1", found.getSessionId());
        assertEquals(HandoffStatus.PENDING, found.getStatus());

        assertTrue(store.findAll().stream().anyMatch(t -> t.getId().equals(id)));
        assertTrue(store.findByStatus(HandoffStatus.PENDING).stream().anyMatch(t -> t.getId().equals(id)));
    }

    @Test
    void update_shouldPersistClaimAndResolveState() {
        String id = "HO-jdbc-it-" + UUID.randomUUID();
        HandoffTicket ticket = new HandoffTicket(id, "s1", "test", System.currentTimeMillis());
        store.save(ticket);

        ticket.claim("alice", System.currentTimeMillis());
        store.update(ticket);

        HandoffTicket claimed = store.find(id).orElseThrow();
        assertEquals(HandoffStatus.CLAIMED, claimed.getStatus());
        assertEquals("alice", claimed.getClaimedBy());

        claimed.resolve("已处理完毕", System.currentTimeMillis());
        store.update(claimed);

        HandoffTicket resolved = store.find(id).orElseThrow();
        assertEquals(HandoffStatus.RESOLVED, resolved.getStatus());
        assertEquals("已处理完毕", resolved.getResolutionNote());

        List<HandoffTicket> resolvedList = store.findByStatus(HandoffStatus.RESOLVED);
        assertTrue(resolvedList.stream().anyMatch(t -> t.getId().equals(id)));
    }

    @Test
    void handoffConfig_shouldSelectJdbcStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanHandoff().setStoreMode("jdbc");
        props.getSession().getMysql().setHost(HOST);
        props.getSession().getMysql().setPort(PORT);
        props.getSession().getMysql().setDatabase("agent_scope_customer_work");
        props.getSession().getMysql().setUsername("root");
        props.getSession().getMysql().setPassword("root");

        HandoffStore selected = new HandoffConfig().handoffStore(props);
        assertInstanceOf(JdbcHandoffStore.class, selected, "store-mode=jdbc 应装配 JdbcHandoffStore");
    }
}
