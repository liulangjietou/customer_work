package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.richard.fyoung.customerwork.infra.config.properties.SessionProperties;

/**
 * Redis 会话持久化测试（默认对接 localhost:6379，密码 123456）。
 *
 * <p>当 Redis 不可达时<b>自动跳过</b>（assumeTrue），保证 {@code mvn test} 在无 Redis 的环境
 * 仍然通过；在 Redis 在线的机器上则真实执行存-取-删往返，验证 {@code RedisSession} 可用。
 * 地址与密码可由 {@code ADMIN_REDIS_HOST}/{@code ADMIN_REDIS_PORT}/
 * {@code ADMIN_REDIS_PASSWORD} 覆盖，既与运行期配置同源，也允许本机无密码 Redis 参与真实门禁；
 * CI 未覆盖时仍使用 123456。</p>
 * @author owlzhangfq@gmail.com
 */
class RedisSessionPersistenceTest {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final String DEFAULT_PASSWORD = "123456";

    private AgentStateStore store;

    @BeforeEach
    void setUp() {
        String host = envOrDefault("ADMIN_REDIS_HOST", DEFAULT_HOST);
        int port = Integer.parseInt(envOrDefault("ADMIN_REDIS_PORT", String.valueOf(DEFAULT_PORT)));
        String password = System.getenv("ADMIN_REDIS_PASSWORD");
        if (password == null) {
            password = DEFAULT_PASSWORD;
        }
        assumeTrue(SessionPersistenceTestSupport.reachable(host, port),
            "Redis 不可达（" + host + ":" + port + "），跳过该测试");

        SessionProperties cfg = new CustomerWorkProperties().getSession();
        cfg.setMode("redis");
        cfg.getRedis().setHost(host);
        cfg.getRedis().setPort(port);
        cfg.getRedis().setPassword(password);
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

    private String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
