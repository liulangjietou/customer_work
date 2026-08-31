package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 登录拼图存储的真服务门禁，覆盖 Lua 原子消费、TTL、并发重放与故障关闭。
 *
 * <p>真实 Redis 用例在服务不可达或鉴权不匹配时按仓库惯例跳过；故障关闭用例使用本进程
 * black-hole 端口独立执行，不依赖外部 Redis 的可用状态。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class RedissonLoginCaptchaStoreIntegrationTest {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int PROBE_TIMEOUT_MILLIS = 800;
    private static final String KEY_PREFIX = "cw:admin:login-captcha:";
    private static final String FINGERPRINT = "fingerprint";

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class LiveRedis {

        private RedissonClient redisson;
        private RedissonLoginCaptchaStore store;

        @BeforeAll
        void setUp() {
            String host = envOrDefault("ADMIN_REDIS_HOST", DEFAULT_HOST);
            int port = Integer.parseInt(envOrDefault("ADMIN_REDIS_PORT", String.valueOf(DEFAULT_PORT)));
            assumeTrue(reachable(host, port),
                "Redis 不可达（" + host + ":" + port + "），跳过真实 Redis 用例");
            try {
                redisson = createClient(host, port, false);
                String probeKey = KEY_PREFIX + "probe:" + UUID.randomUUID();
                redisson.getBucket(probeKey, StringCodec.INSTANCE).set("1", Duration.ofSeconds(5));
                redisson.getBucket(probeKey, StringCodec.INSTANCE).delete();
                store = new RedissonLoginCaptchaStore(redisson);
            } catch (Exception e) {
                shutdown(redisson);
                assumeTrue(false, "Redis 鉴权或连接配置不可用，跳过真实 Redis 用例");
            }
        }

        @AfterAll
        void tearDown() {
            shutdown(redisson);
        }

        @Test
        void credential_shouldKeepOnFingerprintMismatch_thenConsumeOnce_andExpireBothTtls()
            throws InterruptedException {
            String mismatchId = UUID.randomUUID().toString();
            String expiringId = UUID.randomUUID().toString();
            String expiringProofHash = UUID.randomUUID().toString();
            RBucket<String> mismatchBucket = bucket("challenge:", mismatchId);
            RBucket<String> challengeBucket = bucket("challenge:", expiringId);
            RBucket<String> proofBucket = bucket("proof:", expiringProofHash);
            long now = System.currentTimeMillis();
            try {
                store.saveChallenge(mismatchId,
                    new LoginCaptchaStore.ChallengeState(
                        FINGERPRINT, now, now + 10_000, 620, 25), 10);

                assertEquals(LoginCaptchaStore.ConsumeStatus.FINGERPRINT_MISMATCH,
                    store.consumeChallenge(mismatchId, "other-fingerprint").status());
                assertTrue(mismatchBucket.isExists(), "错误指纹不能烧掉真实用户的 challenge");
                assertEquals(LoginCaptchaStore.ConsumeStatus.MATCHED,
                    store.consumeChallenge(mismatchId, FINGERPRINT).status());
                assertEquals(LoginCaptchaStore.ConsumeStatus.NOT_FOUND,
                    store.consumeChallenge(mismatchId, FINGERPRINT).status());

                store.saveChallenge(expiringId,
                    new LoginCaptchaStore.ChallengeState(
                        FINGERPRINT, now, now + 2_000, 620, 25), 2);
                store.saveProof(expiringProofHash,
                    new LoginCaptchaStore.ProofState(FINGERPRINT, now + 2_000), 2);
                assertRedisTtl(challengeBucket, "challenge");
                assertRedisTtl(proofBucket, "proof");

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((challengeBucket.isExists() || proofBucket.isExists())
                    && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                assertFalse(challengeBucket.isExists(), "challenge 应由 Redis TTL 自动清理");
                assertFalse(proofBucket.isExists(), "proof 应由 Redis TTL 自动清理");
                assertEquals(LoginCaptchaStore.ConsumeStatus.NOT_FOUND,
                    store.consumeChallenge(expiringId, FINGERPRINT).status());
                assertEquals(LoginCaptchaStore.ConsumeStatus.NOT_FOUND,
                    store.consumeProof(expiringProofHash, FINGERPRINT).status());
            } finally {
                mismatchBucket.delete();
                challengeBucket.delete();
                proofBucket.delete();
            }
        }

        @Test
        void proof_shouldAllowExactlyOneMatchedConsumer_underConcurrentReplay() throws Exception {
            String proofHash = UUID.randomUUID().toString();
            RBucket<String> proofBucket = bucket("proof:", proofHash);
            store.saveProof(proofHash,
                new LoginCaptchaStore.ProofState(FINGERPRINT, System.currentTimeMillis() + 10_000), 10);

            int concurrency = 16;
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CountDownLatch ready = new CountDownLatch(concurrency);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<LoginCaptchaStore.ConsumeStatus>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < concurrency; i++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return store.consumeProof(proofHash, FINGERPRINT).status();
                    }));
                }

                assertTrue(ready.await(5, TimeUnit.SECONDS), "并发消费线程未能及时就绪");
                start.countDown();

                long matched = 0;
                long notFound = 0;
                for (Future<LoginCaptchaStore.ConsumeStatus> future : futures) {
                    LoginCaptchaStore.ConsumeStatus status = future.get(10, TimeUnit.SECONDS);
                    if (status == LoginCaptchaStore.ConsumeStatus.MATCHED) {
                        matched++;
                    }
                    if (status == LoginCaptchaStore.ConsumeStatus.NOT_FOUND) {
                        notFound++;
                    }
                }
                assertEquals(1, matched, "同一 proof 只能有一个并发请求消费成功");
                assertEquals(concurrency - 1L, notFound, "其余并发重放必须全部失败");
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发测试线程未正常退出");
                proofBucket.delete();
            }
        }

        @Test
        void challenge_shouldFailClosedForLegacyUnversionedRedisValue() {
            String challengeId = UUID.randomUUID().toString();
            RBucket<String> challengeBucket = bucket("challenge:", challengeId);
            long now = System.currentTimeMillis();
            try {
                challengeBucket.set(
                    FINGERPRINT + ":" + now + ":" + (now + 10_000), Duration.ofSeconds(10));

                assertThrows(IllegalStateException.class,
                    () -> store.consumeChallenge(challengeId, FINGERPRINT));
                assertTrue(challengeBucket.isExists(),
                    "旧格式没有通过版本校验时不得被误当成匹配 challenge 消费");
            } finally {
                challengeBucket.delete();
            }
        }

        private RBucket<String> bucket(String type, String id) {
            return redisson.getBucket(KEY_PREFIX + type + id, StringCodec.INSTANCE);
        }

        private void assertRedisTtl(RBucket<String> bucket, String type) {
            long ttlMillis = bucket.remainTimeToLive();
            assertTrue(ttlMillis > 0 && ttlMillis <= 2_000,
                type + " TTL 应真实落到 Redis，实际 " + ttlMillis + "ms");
        }
    }

    @Test
    void write_shouldPropagateRealCommandTimeout_withoutExternalRedis() throws Exception {
        try (ServerSocket blackHole = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Config config = new Config();
            config.setLazyInitialization(true);
            config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + blackHole.getLocalPort())
                .setConnectTimeout(300)
                .setTimeout(300)
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(1);
            RedissonClient unavailable = Redisson.create(config);
            try {
                RedissonLoginCaptchaStore unavailableStore = new RedissonLoginCaptchaStore(unavailable);
                assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(RuntimeException.class, () -> unavailableStore.saveProof(
                        UUID.randomUUID().toString(),
                        new LoginCaptchaStore.ProofState(
                            FINGERPRINT, System.currentTimeMillis() + 10_000),
                        10)));
            } finally {
                shutdown(unavailable);
            }
        }
    }

    private RedissonClient createClient(String host, int port, boolean lazyInitialization) {
        String addressHost = "localhost".equalsIgnoreCase(host) ? "127.0.0.1" : host;
        Config config = new Config();
        config.setLazyInitialization(lazyInitialization);
        SingleServerConfig server = config.useSingleServer()
            .setAddress("redis://" + addressHost + ":" + port)
            .setConnectionMinimumIdleSize(1)
            .setConnectionPoolSize(4);
        String password = System.getenv("ADMIN_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }

    private void shutdown(RedissonClient client) {
        if (client != null && !client.isShutdown()) {
            client.shutdown();
        }
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
