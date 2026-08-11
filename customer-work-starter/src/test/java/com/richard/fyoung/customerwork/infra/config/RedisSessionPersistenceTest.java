package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.richard.fyoung.customerwork.infra.config.properties.SessionProperties;

/**
 * Redis 会话持久化测试（对接本机 Redis：localhost:6379，密码 123456）。
 *
 * <p>当 Redis 不可达时<b>自动跳过</b>（assumeTrue），保证 {@code mvn test} 在无 Redis 的环境
 * 仍然通过；在 Redis 在线的机器上则真实执行存-取-删往返，验证 {@code RedisSession} 可用。</p>
 * @author owlzhangfq@gmail.com
 */
class RedisSessionPersistenceTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;
    private static final String PASSWORD = "123456";

    private AgentStateStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(SessionPersistenceTestSupport.reachable(HOST, PORT),
            "Redis 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        SessionProperties cfg = new CustomerWorkProperties().getSession();
        cfg.setMode("redis");
        cfg.getRedis().setHost(HOST);
        cfg.getRedis().setPort(PORT);
        cfg.getRedis().setPassword(PASSWORD);
        cfg.getRedis().setKeyPrefix("customer-work-it");

        store = new SessionConfig().buildStateStore(cfg);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void saveGetDelete_overRedis() {
        SessionPersistenceTestSupport.assertSaveGetDelete(store, "it-redis:" + UUID.randomUUID());
    }
}
