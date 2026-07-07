package com.richard.fyoung.customerwork.feedback;

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
 * JDBC 用户反馈存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，见 mysql/schema.sql 中的 cw_message_feedback 表）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；MySQL 在线的机器上真实执行存-取往返，
 * 验证 {@link JdbcFeedbackStore}（含自动建表、upsert 覆盖）。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcFeedbackStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private JdbcFeedbackStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setHost(HOST);
        cfg.setPort(PORT);
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");

        DataSource dataSource = new FeedbackConfig().buildDataSource(cfg);
        store = new JdbcFeedbackStore(dataSource);
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
        String messageId = "MSG-jdbc-it-" + UUID.randomUUID();
        MessageFeedback fb = new MessageFeedback(messageId, "s1", FeedbackType.DOWN, "test", System.currentTimeMillis());
        store.save(fb);

        MessageFeedback found = store.find(messageId).orElseThrow();
        assertEquals(FeedbackType.DOWN, found.type());
        assertEquals("test", found.comment());

        List<MessageFeedback> bySession = store.findBySession("s1");
        assertTrue(bySession.stream().anyMatch(f -> f.messageId().equals(messageId)));
    }

    @Test
    void save_shouldUpsertBySameMessageId() {
        String messageId = "MSG-jdbc-it-" + UUID.randomUUID();
        store.save(new MessageFeedback(messageId, "s1", FeedbackType.UP, null, System.currentTimeMillis()));
        store.save(new MessageFeedback(messageId, "s1", FeedbackType.DOWN, "改主意了", System.currentTimeMillis()));

        MessageFeedback found = store.find(messageId).orElseThrow();
        assertEquals(FeedbackType.DOWN, found.type());
        assertEquals("改主意了", found.comment());
    }

    @Test
    void feedbackConfig_shouldSelectJdbcStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getFeedback().setStoreMode("jdbc");
        props.getSession().getMysql().setHost(HOST);
        props.getSession().getMysql().setPort(PORT);
        props.getSession().getMysql().setDatabase("agent_scope_customer_work");
        props.getSession().getMysql().setUsername("root");
        props.getSession().getMysql().setPassword("root");

        FeedbackStore selected = new FeedbackConfig().feedbackStore(props);
        assertInstanceOf(JdbcFeedbackStore.class, selected, "store-mode=jdbc 应装配 JdbcFeedbackStore");
    }
}
