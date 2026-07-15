package com.richard.fyoung.customerwork.feedback;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.feedback.mapper.FeedbackMapper;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 用户反馈存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，表 cw_message_feedback 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；MySQL 在线的机器上真实执行存-取往返，
 * 验证 {@link MybatisFeedbackStore}（含 upsert 覆盖）。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisFeedbackStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private FeedbackMapper mapper;
    private MybatisFeedbackStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-feedback-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        mapper = MybatisTestSupport.mapper(dataSource, FeedbackMapper.class);
        store = new MybatisFeedbackStore(mapper);
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
    void saveAndFind_shouldRoundTrip() {
        String messageId = "MSG-mybatis-it-" + UUID.randomUUID();
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
        String messageId = "MSG-mybatis-it-" + UUID.randomUUID();
        store.save(new MessageFeedback(messageId, "s1", FeedbackType.UP, null, System.currentTimeMillis()));
        store.save(new MessageFeedback(messageId, "s1", FeedbackType.DOWN, "改主意了", System.currentTimeMillis()));

        MessageFeedback found = store.find(messageId).orElseThrow();
        assertEquals(FeedbackType.DOWN, found.type());
        assertEquals("改主意了", found.comment());
    }

    @Test
    void feedbackConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getFeedback().setStoreMode("jdbc");

        FeedbackStore selected = new FeedbackConfig().feedbackStore(props, singletonProvider(mapper));
        assertInstanceOf(MybatisFeedbackStore.class, selected, "store-mode=jdbc 应装配 MybatisFeedbackStore");
    }

    /** 最小 {@link ObjectProvider}：仅 {@code getObject()} 返回给定 Bean，供 Config 单测在无 Spring 容器下取 Mapper。 */
    private static <T> ObjectProvider<T> singletonProvider(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }
        };
    }
}
