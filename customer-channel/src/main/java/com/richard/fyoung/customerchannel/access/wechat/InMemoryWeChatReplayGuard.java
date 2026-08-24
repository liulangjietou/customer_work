package com.richard.fyoung.customerchannel.access.wechat;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单实例微信回放保护，仅用于测试/开发。生产多副本应使用 Redis 实现。
 *
 * <p>所有状态变更收敛在同一把锁内，保证检查与占位原子；每次写入顺便清理过期项并限制容量。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryWeChatReplayGuard implements WeChatReplayGuard {

    private static final String NONCE_PREFIX = "nonce:";
    private static final String MESSAGE_PREFIX = "message:";

    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<String, Long> expiresAtByKey = new LinkedHashMap<>();

    public InMemoryWeChatReplayGuard(int maxEntries) {
        this(maxEntries, Clock.systemUTC());
    }

    InMemoryWeChatReplayGuard(int maxEntries, Clock clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("wechat replay memoryMaxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    @Override
    public boolean claimNonce(String appId, String nonce, Duration ttl) {
        return claim(NONCE_PREFIX + appId + ":" + nonce, ttl);
    }

    @Override
    public boolean claimMessage(String appId, String messageKey, Duration ttl) {
        return claim(MESSAGE_PREFIX + appId + ":" + messageKey, ttl);
    }

    private synchronized boolean claim(String key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("wechat replay ttl must be positive");
        }
        long now = clock.millis();
        purgeExpired(now);
        Long currentExpiry = expiresAtByKey.get(key);
        if (currentExpiry != null && currentExpiry > now) {
            return false;
        }
        expiresAtByKey.put(key, Math.addExact(now, ttl.toMillis()));
        trimToCapacity();
        return true;
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, Long>> iterator = expiresAtByKey.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    private void trimToCapacity() {
        Iterator<String> iterator = expiresAtByKey.keySet().iterator();
        while (expiresAtByKey.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
