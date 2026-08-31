package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 进程内登录拼图凭据存储的过期、容量与一次性消费语义。 */
class InMemoryLoginCaptchaStoreTest {

    private static final String FINGERPRINT = "fingerprint";

    @Test
    void consumeChallenge_shouldTreatAbsoluteExpiryAsNotFound() {
        MutableClock clock = new MutableClock(1_000L);
        InMemoryLoginCaptchaStore store = new InMemoryLoginCaptchaStore(2, clock);
        store.saveChallenge("expired", challenge(FINGERPRINT, 999L), 120);

        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ChallengeState> result =
            store.consumeChallenge("expired", FINGERPRINT);

        assertEquals(LoginCaptchaStore.ConsumeStatus.NOT_FOUND, result.status());
    }

    @Test
    void consumeProof_shouldTreatAbsoluteExpiryAsNotFound() {
        MutableClock clock = new MutableClock(1_000L);
        InMemoryLoginCaptchaStore store = new InMemoryLoginCaptchaStore(2, clock);
        store.saveProof("proof", proof(FINGERPRINT, 999L), 120);

        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ProofState> result =
            store.consumeProof("proof", FINGERPRINT);

        assertEquals(LoginCaptchaStore.ConsumeStatus.NOT_FOUND, result.status());
    }

    @Test
    void consumeChallenge_shouldAtomicallyAllowOnlyOneConcurrentConsumer() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        InMemoryLoginCaptchaStore store = new InMemoryLoginCaptchaStore(2, clock);
        store.saveChallenge("challenge", challenge(FINGERPRINT, 2_000L), 120);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ChallengeState>>> results = List.of(
                executor.submit(consumeAfterBarrier(store, ready, start)),
                executor.submit(consumeAfterBarrier(store, ready, start)));
            ready.await();
            start.countDown();

            long matched = 0;
            long missing = 0;
            for (Future<LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ChallengeState>> result : results) {
                if (result.get().status() == LoginCaptchaStore.ConsumeStatus.MATCHED) {
                    matched++;
                } else if (result.get().status() == LoginCaptchaStore.ConsumeStatus.NOT_FOUND) {
                    missing++;
                }
            }
            assertEquals(1, matched);
            assertEquals(1, missing);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void save_shouldFailFastWhenCapacityIsExceededButAllowOverwrite() {
        MutableClock clock = new MutableClock(1_000L);
        InMemoryLoginCaptchaStore store = new InMemoryLoginCaptchaStore(1, clock);
        store.saveProof("existing", proof(FINGERPRINT, 2_000L), 120);

        assertDoesNotThrow(() -> store.saveProof("existing", proof("new-fingerprint", 2_000L), 120));
        assertThrows(IllegalStateException.class,
            () -> store.saveProof("new", proof(FINGERPRINT, 2_000L), 120));
    }

    @Test
    void save_shouldPurgeExpiredEntriesBeforeCheckingCapacity() {
        MutableClock clock = new MutableClock(1_000L);
        InMemoryLoginCaptchaStore store = new InMemoryLoginCaptchaStore(1, clock);
        store.saveChallenge("expired", challenge(FINGERPRINT, 1_000L), 120);

        assertDoesNotThrow(() -> store.saveChallenge("fresh", challenge(FINGERPRINT, 2_000L), 120));
    }

    @Test
    void saveProof_shouldKeepHardCapacityLimit_underConcurrentDistinctKeys() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        InMemoryLoginCaptchaStore store = new InMemoryLoginCaptchaStore(1, clock);

        assertConcurrentCapacityLimit(key ->
            store.saveProof(key, proof(FINGERPRINT, 2_000L), 120));
    }

    @Test
    void saveChallenge_shouldKeepHardCapacityLimit_underConcurrentDistinctKeys() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        InMemoryLoginCaptchaStore store = new InMemoryLoginCaptchaStore(1, clock);

        assertConcurrentCapacityLimit(key ->
            store.saveChallenge(key, challenge(FINGERPRINT, 2_000L), 120));
    }

    private void assertConcurrentCapacityLimit(StoreWriter writer) throws Exception {
        int concurrency = 24;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                String key = "credential-" + index;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        writer.save(key);
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "并发写入线程未及时就绪");
            start.countDown();

            long saved = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    saved++;
                }
            }
            assertEquals(1, saved, "不同 key 并发写入也不能突破硬容量上限");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发写入线程未正常退出");
        }
    }

    @FunctionalInterface
    private interface StoreWriter {
        void save(String key);
    }

    private Callable<LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ChallengeState>> consumeAfterBarrier(
        InMemoryLoginCaptchaStore store, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return store.consumeChallenge("challenge", FINGERPRINT);
        };
    }

    private LoginCaptchaStore.ChallengeState challenge(String fingerprint, long expireAtMs) {
        return new LoginCaptchaStore.ChallengeState(fingerprint, 0L, expireAtMs, 620, 25);
    }

    private LoginCaptchaStore.ProofState proof(String fingerprint, long expireAtMs) {
        return new LoginCaptchaStore.ProofState(fingerprint, expireAtMs);
    }

    private static final class MutableClock extends Clock {
        private final long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
