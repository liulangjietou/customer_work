package com.richard.fyoung.customerchannel.access.wechat;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis 回放保护测试：原子 SET NX + TTL，且 Redis key 不含平台原始标识。 */
class RedisWeChatReplayGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseHashedKeyAndAtomicTtlClaim() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<Object> bucket = mock(RBucket.class);
        when(client.getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent("1", Duration.ofMinutes(10))).thenReturn(true);
        RedisWeChatReplayGuard guard = new RedisWeChatReplayGuard(client, "prefix:");

        boolean claimed = guard.claimNonce("wx-sensitive-app", "secret-nonce", Duration.ofMinutes(10));

        assertTrue(claimed);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(client).getBucket(key.capture());
        assertTrue(key.getValue().startsWith("prefix:nonce:"));
        assertFalse(key.getValue().contains("wx-sensitive-app"));
        assertFalse(key.getValue().contains("secret-nonce"));
        verify(bucket).setIfAbsent("1", Duration.ofMinutes(10));
    }
}
