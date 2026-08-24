package com.richard.fyoung.customerchannel.access.wechat;

import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Redis 原子占位实现，Redis key 只包含摘要，不泄露 appId、nonce 或平台消息标识。 */
public class RedisWeChatReplayGuard implements WeChatReplayGuard {

    private static final String NONCE_KIND = "nonce";
    private static final String MESSAGE_KIND = "message";

    private final RedissonClient redissonClient;
    private final String keyPrefix;

    public RedisWeChatReplayGuard(RedissonClient redissonClient, String keyPrefix) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public boolean claimNonce(String appId, String nonce, Duration ttl) {
        return claim(NONCE_KIND, appId, nonce, ttl);
    }

    @Override
    public boolean claimMessage(String appId, String messageKey, Duration ttl) {
        return claim(MESSAGE_KIND, appId, messageKey, ttl);
    }

    private boolean claim(String kind, String appId, String value, Duration ttl) {
        String key = keyPrefix + kind + ":" + digest(appId + "\n" + value);
        return redissonClient.getBucket(key).setIfAbsent("1", ttl);
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
