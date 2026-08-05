package com.richard.fyoung.customeradmin.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 登录态 Redis 持久化门控集成测试：验证"服务重启后登录态还在"这件事本身。
 *
 * <p>用两个互相独立的 {@link SaTokenDao} 实例模拟重启前后的两个进程——第一个写入，第二个（全新的
 * 连接工厂 + 全新的 DAO，等价于新起的 JVM）能读到同一份数据，就证明登录态不再依附于进程内存。
 * 顺带验证 TTL 真落到了 Redis 上，以及 {@link SaSession}（token session 里存了 username）能
 * 跨实例正确反序列化——这是从内存 DAO 换到 Redis 后唯一新增的失败面。</p>
 *
 * <p>Redis 不可达或需要鉴权而本机未提供密码时自动跳过（同仓库既有门控约定），
 * 地址/密码取 {@code ADMIN_REDIS_HOST}/{@code ADMIN_REDIS_PORT}/{@code ADMIN_REDIS_PASSWORD}，
 * 与运行期用的是同一批环境变量。</p>
 * @author owlzhangfq@gmail.com
 */
class SaTokenRedisPersistenceIntegrationTest {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int PROBE_TIMEOUT_MILLIS = 800;
    /** 与 application.yml 的 sa-token.timeout 默认值一致：24 小时 */
    private static final long TIMEOUT_SECONDS = 86400L;

    @Test
    void loginState_shouldSurviveProcessRestart() {
        String host = envOrDefault("ADMIN_REDIS_HOST", DEFAULT_HOST);
        int port = Integer.parseInt(envOrDefault("ADMIN_REDIS_PORT", String.valueOf(DEFAULT_PORT)));
        assumeTrue(reachable(host, port), "Redis 不可达（" + host + ":" + port + "），跳过该集成测试");

        String key = "satoken-it:" + UUID.randomUUID();
        String sessionId = "satoken-it-session:" + UUID.randomUUID();

        LettuceConnectionFactory writerFactory = connectionFactory(host, port);
        SaTokenDao writer = new AdminSaTokenDaoConfig().saTokenDao(writerFactory);
        try {
            assumeTrue(pingable(writer, key), "Redis 需要鉴权但未提供 ADMIN_REDIS_PASSWORD，跳过该集成测试");

            writer.set(key, "10001", TIMEOUT_SECONDS);
            SaSession session = new SaSession(sessionId);
            session.set("username", "admin");
            writer.setObject(sessionId, session, TIMEOUT_SECONDS);
        } finally {
            writerFactory.destroy();
        }

        // 这里等价于"服务重启"：连接工厂与 DAO 全部换新，进程内不残留任何登录态
        LettuceConnectionFactory readerFactory = connectionFactory(host, port);
        SaTokenDao reader = new AdminSaTokenDaoConfig().saTokenDao(readerFactory);
        try {
            assertEquals("10001", reader.get(key));
            // TTL 由 Redis 兜底：允许写入到读取之间的秒级损耗，只断言仍在 24 小时量级
            long timeout = reader.getTimeout(key);
            assertTrue(timeout > TIMEOUT_SECONDS - 60 && timeout <= TIMEOUT_SECONDS,
                "TTL 应仍在 24 小时量级，实际 " + timeout);

            SaSession restored = (SaSession) reader.getObject(sessionId);
            assertNotNull(restored, "SaSession 跨实例反序列化失败");
            assertEquals("admin", restored.getString("username"));

            reader.delete(key);
            reader.deleteObject(sessionId);
            assertNull(reader.get(key));
        } finally {
            readerFactory.destroy();
        }
    }

    /** 写一次并读回，借此把 NOAUTH 这类鉴权失败暴露出来（连得上端口不等于能用） */
    private boolean pingable(SaTokenDao dao, String key) {
        try {
            dao.set(key + ":probe", "1", 10);
            dao.delete(key + ":probe");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private LettuceConnectionFactory connectionFactory(String host, int port) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        String password = System.getenv("ADMIN_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return factory;
    }

    private String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
