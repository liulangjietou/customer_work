package com.richard.fyoung.customeradmin.auth.guard;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 登录滑块的进程内存储，供没有 Redisson Bean 的单实例部署在启动时选用。
 *
 * <p>消费通过 {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} 完成，
 * 同一键的指纹判断与删除不可被并发请求拆开。challenge/proof 各自用容量锁串行完成
 * “清理过期项—检查上限—写入”，避免不同 key 并发突破硬容量上限。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryLoginCaptchaStore implements LoginCaptchaStore {

    private static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final Map<String, ChallengeState> challenges = new ConcurrentHashMap<>();
    private final Map<String, ProofState> proofs = new ConcurrentHashMap<>();
    private final Object challengeCapacityLock = new Object();
    private final Object proofCapacityLock = new Object();
    private final int maxEntries;
    private final Clock clock;

    public InMemoryLoginCaptchaStore() {
        this(DEFAULT_MAX_ENTRIES, Clock.systemUTC());
    }

    InMemoryLoginCaptchaStore(int maxEntries, Clock clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void saveChallenge(String challengeId, ChallengeState state, int ttlSeconds) {
        synchronized (challengeCapacityLock) {
            purgeExpired(challenges, ChallengeState::expireAtMs);
            save(challenges, challengeId, state);
        }
    }

    @Override
    public ConsumeResult<ChallengeState> consumeChallenge(String challengeId, String fingerprint) {
        return consume(challenges, challengeId, fingerprint,
            ChallengeState::fingerprint, ChallengeState::expireAtMs);
    }

    @Override
    public void saveProof(String proofHash, ProofState state, int ttlSeconds) {
        synchronized (proofCapacityLock) {
            purgeExpired(proofs, ProofState::expireAtMs);
            save(proofs, proofHash, state);
        }
    }

    @Override
    public ConsumeResult<ProofState> consumeProof(String proofHash, String fingerprint) {
        return consume(proofs, proofHash, fingerprint, ProofState::fingerprint, ProofState::expireAtMs);
    }

    private <T> void save(Map<String, T> values, String key, T state) {
        if (!values.containsKey(key) && values.size() >= maxEntries) {
            throw new IllegalStateException("login captcha in-memory store capacity exceeded");
        }
        values.put(key, state);
    }

    private <T> ConsumeResult<T> consume(Map<String, T> values, String key, String fingerprint,
                                         Function<T, String> fingerprintOf,
                                         Function<T, Long> expireAtOf) {
        AtomicReference<T> matched = new AtomicReference<>();
        AtomicBoolean fingerprintMismatch = new AtomicBoolean(false);
        long now = clock.millis();
        values.computeIfPresent(key, (ignored, state) -> {
            if (expireAtOf.apply(state) <= now) {
                return null;
            }
            if (!Objects.equals(fingerprintOf.apply(state), fingerprint)) {
                fingerprintMismatch.set(true);
                return state;
            }
            matched.set(state);
            return null;
        });
        if (matched.get() != null) {
            return ConsumeResult.matched(matched.get());
        }
        return fingerprintMismatch.get()
            ? ConsumeResult.fingerprintMismatch()
            : ConsumeResult.notFound();
    }

    private <T> void purgeExpired(Map<String, T> values, Function<T, Long> expireAtOf) {
        long now = clock.millis();
        values.entrySet().removeIf(entry -> expireAtOf.apply(entry.getValue()) <= now);
    }
}
