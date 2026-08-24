package com.richard.fyoung.customerchannel.access.wechat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link InMemoryWeChatReplayGuard} 原子占位、TTL 与有界容量测试。 */
class InMemoryWeChatReplayGuardTest {

    @Test
    void sameNonceAndMessageShouldOnlyBeClaimedOnceWithinTtl() {
        InMemoryWeChatReplayGuard guard = new InMemoryWeChatReplayGuard(10);

        assertTrue(guard.claimNonce("app", "nonce", Duration.ofMinutes(1)));
        assertFalse(guard.claimNonce("app", "nonce", Duration.ofMinutes(1)));
        assertTrue(guard.claimMessage("app", "message", Duration.ofMinutes(1)));
        assertFalse(guard.claimMessage("app", "message", Duration.ofMinutes(1)));
    }

    @Test
    void expiredClaimShouldBecomeAvailableAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
        InMemoryWeChatReplayGuard guard = new InMemoryWeChatReplayGuard(10, clock);

        assertTrue(guard.claimNonce("app", "nonce", Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(11));

        assertTrue(guard.claimNonce("app", "nonce", Duration.ofSeconds(10)));
    }

    @Test
    void capacityShouldEvictOldestClaim() {
        InMemoryWeChatReplayGuard guard = new InMemoryWeChatReplayGuard(2);
        Duration ttl = Duration.ofHours(1);

        guard.claimNonce("app", "first", ttl);
        guard.claimNonce("app", "second", ttl);
        guard.claimNonce("app", "third", ttl);

        assertTrue(guard.claimNonce("app", "first", ttl));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
