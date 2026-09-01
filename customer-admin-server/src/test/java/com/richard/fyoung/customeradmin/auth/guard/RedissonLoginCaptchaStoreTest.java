package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis 登录拼图存储的原子消费与请求期 fail-closed 边界。 */
class RedissonLoginCaptchaStoreTest {

    private static final String FINGERPRINT = "fingerprint";

    private RedissonClient redisson;
    private RScript script;
    private RedissonLoginCaptchaStore store;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        store = new RedissonLoginCaptchaStore(redisson);
    }

    @Test
    void consumeChallenge_shouldUseAtomicLuaScriptForMatchingFingerprint() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE),
            org.mockito.ArgumentMatchers.<List<Object>>any(), eq("v2"), eq(FINGERPRINT)))
            .thenReturn("v2:" + FINGERPRINT + ":100:200:620:25");

        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ChallengeState> result =
            store.consumeChallenge("challenge-id", FINGERPRINT);

        assertEquals(LoginCaptchaStore.ConsumeStatus.MATCHED, result.status());
        assertEquals(new LoginCaptchaStore.ChallengeState(
            FINGERPRINT, 100L, 200L, 620, 25), result.state());
        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), lua.capture(), eq(RScript.ReturnType.VALUE),
            eq(List.of("cw:admin:login-captcha:challenge:challenge-id")), eq("v2"), eq(FINGERPRINT));
        assertTrue(lua.getValue().contains("redis.call('DEL', KEYS[1])"));
        assertTrue(lua.getValue().contains("return '__FINGERPRINT_MISMATCH__'"));
        assertTrue(lua.getValue().contains("return '__MALFORMED_STATE__'"));
    }

    @Test
    void consumeProof_shouldKeepCredentialWhenFingerprintDoesNotMatch() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE),
            org.mockito.ArgumentMatchers.<List<Object>>any(), eq("wrong-fingerprint")))
            .thenReturn("__FINGERPRINT_MISMATCH__");

        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ProofState> result =
            store.consumeProof("proof-hash", "wrong-fingerprint");

        assertEquals(LoginCaptchaStore.ConsumeStatus.FINGERPRINT_MISMATCH, result.status());
        verify(redisson, never()).getBucket(anyString(), any());
    }

    @Test
    void consumeProof_shouldPropagateWhenRedisThrows() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class,
            () -> store.consumeProof("proof-hash", FINGERPRINT));
    }

    @Test
    void consumeProof_shouldReturnNotFoundWhenRedisReturnsNull() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE),
            org.mockito.ArgumentMatchers.<List<Object>>any(), eq(FINGERPRINT))).thenReturn(null);

        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ProofState> result =
            store.consumeProof("proof-hash", FINGERPRINT);

        assertEquals(LoginCaptchaStore.ConsumeStatus.NOT_FOUND, result.status());
    }

    @Test
    void saveChallenge_shouldPropagateAmbiguousRedisFailure() {
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(
            "cw:admin:login-captcha:challenge:challenge-id", StringCodec.INSTANCE))
            .thenReturn(bucket);
        doThrow(new IllegalStateException("redis timeout"))
            .when(bucket).set(anyString(), eq(Duration.ofSeconds(60)));

        assertThrows(IllegalStateException.class, () -> store.saveChallenge(
            "challenge-id", new LoginCaptchaStore.ChallengeState(
                FINGERPRINT, 100L, 200L, 620, 25), 60));
    }

    @Test
    void saveProof_shouldPropagateAmbiguousRedisFailure() {
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(
            "cw:admin:login-captcha:proof:proof-hash", StringCodec.INSTANCE))
            .thenReturn(bucket);
        doThrow(new IllegalStateException("redis timeout"))
            .when(bucket).set(anyString(), eq(Duration.ofSeconds(60)));

        assertThrows(IllegalStateException.class, () -> store.saveProof(
            "proof-hash", new LoginCaptchaStore.ProofState(FINGERPRINT, 200L), 60));
    }

    @Test
    void consumeChallenge_shouldPropagateMalformedPersistedState() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE),
            org.mockito.ArgumentMatchers.<List<Object>>any(), eq("v2"), eq(FINGERPRINT)))
            .thenReturn("v2:" + FINGERPRINT + ":malformed");

        assertThrows(IllegalStateException.class,
            () -> store.consumeChallenge("challenge-id", FINGERPRINT));
    }

    @Test
    void consumeChallenge_shouldFailClosedForLegacyUnversionedState() {
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE),
            org.mockito.ArgumentMatchers.<List<Object>>any(), eq("v2"), eq(FINGERPRINT)))
            .thenReturn("__MALFORMED_STATE__");

        assertThrows(IllegalStateException.class,
            () -> store.consumeChallenge("legacy-challenge", FINGERPRINT));
    }

    @Test
    void saveChallenge_shouldPersistExplicitVersionAndOnlyCompactServerState() {
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(
            "cw:admin:login-captcha:challenge:challenge-id", StringCodec.INSTANCE))
            .thenReturn(bucket);
        LoginCaptchaStore.ChallengeState state = new LoginCaptchaStore.ChallengeState(
            FINGERPRINT, 100L, 200L, 620, 25);

        store.saveChallenge("challenge-id", state, 60);

        verify(bucket).set(eq("v2:" + FINGERPRINT + ":100:200:620:25"),
            eq(Duration.ofSeconds(60)));
    }

    @Test
    void proof_shouldKeepLegacyEncodingAndLuaContractForRollingUpgradeCompatibility() {
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(
            "cw:admin:login-captcha:proof:proof-hash", StringCodec.INSTANCE))
            .thenReturn(bucket);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE),
            org.mockito.ArgumentMatchers.<List<Object>>any(), eq(FINGERPRINT)))
            .thenReturn(FINGERPRINT + ":200");

        store.saveProof("proof-hash", new LoginCaptchaStore.ProofState(FINGERPRINT, 200L), 60);
        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ProofState> result =
            store.consumeProof("proof-hash", FINGERPRINT);

        verify(bucket).set(eq(FINGERPRINT + ":200"), eq(Duration.ofSeconds(60)));
        assertEquals(LoginCaptchaStore.ConsumeStatus.MATCHED, result.status());
        assertEquals(new LoginCaptchaStore.ProofState(FINGERPRINT, 200L), result.state());
        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), lua.capture(), eq(RScript.ReturnType.VALUE),
            eq(List.of("cw:admin:login-captcha:proof:proof-hash")), eq(FINGERPRINT));
        assertTrue(lua.getValue().contains("local prefix = ARGV[1] .. ':'"));
    }
}
