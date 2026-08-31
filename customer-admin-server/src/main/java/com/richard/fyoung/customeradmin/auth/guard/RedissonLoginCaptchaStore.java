package com.richard.fyoung.customeradmin.auth.guard;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;

/**
 * 登录拼图的 Redis 存储。
 *
 * <p>匹配消费用 Lua 在 Redis 内完成“读取指纹、匹配、删除”：匹配时原子消费，
 * 不匹配时保留原值。实例一旦在启动阶段选用 Redis，所有读写异常都必须向上抛；
 * 请求期不能退进程内存储，否则响应状态不确定时可能让已消费的 challenge/proof 复活。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class RedissonLoginCaptchaStore implements LoginCaptchaStore {

    private static final String KEY_PREFIX = "cw:admin:login-captcha:";
    private static final String CHALLENGE_KEY_PREFIX = KEY_PREFIX + "challenge:";
    private static final String PROOF_KEY_PREFIX = KEY_PREFIX + "proof:";
    private static final String CHALLENGE_STATE_VERSION = "v2";
    private static final String FINGERPRINT_MISMATCH = "__FINGERPRINT_MISMATCH__";
    private static final String MALFORMED_STATE = "__MALFORMED_STATE__";

    private static final String CONSUME_VERSIONED_CHALLENGE_SCRIPT =
        "local value = redis.call('GET', KEYS[1]) "
            + "if not value then return nil end "
            + "local versionPrefix = ARGV[1] .. ':' "
            + "if string.sub(value, 1, string.len(versionPrefix)) ~= versionPrefix then "
            + "  return '__MALFORMED_STATE__' "
            + "end "
            + "local fingerprintPrefix = versionPrefix .. ARGV[2] .. ':' "
            + "if string.sub(value, 1, string.len(fingerprintPrefix)) ~= fingerprintPrefix then "
            + "  return '__FINGERPRINT_MISMATCH__' "
            + "end "
            + "redis.call('DEL', KEYS[1]) "
            + "return value";

    /** proof 状态结构未变，保留旧脚本和编码以支持新旧节点滚动升级。 */
    private static final String CONSUME_PROOF_IF_FINGERPRINT_MATCHES_SCRIPT =
        "local value = redis.call('GET', KEYS[1]) "
            + "if not value then return nil end "
            + "local prefix = ARGV[1] .. ':' "
            + "if string.sub(value, 1, string.len(prefix)) ~= prefix then "
            + "  return '__FINGERPRINT_MISMATCH__' "
            + "end "
            + "redis.call('DEL', KEYS[1]) "
            + "return value";

    private final RedissonClient redisson;

    public RedissonLoginCaptchaStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public void saveChallenge(String challengeId, ChallengeState state, int ttlSeconds) {
        bucket(CHALLENGE_KEY_PREFIX + challengeId).set(
            encode(state), Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public ConsumeResult<ChallengeState> consumeChallenge(String challengeId, String fingerprint) {
        String encoded = consume(
            CONSUME_VERSIONED_CHALLENGE_SCRIPT,
            CHALLENGE_KEY_PREFIX + challengeId,
            CHALLENGE_STATE_VERSION,
            fingerprint);
        if (encoded == null) {
            return ConsumeResult.notFound();
        }
        if (FINGERPRINT_MISMATCH.equals(encoded)) {
            return ConsumeResult.fingerprintMismatch();
        }
        if (MALFORMED_STATE.equals(encoded)) {
            throw malformed("challenge", "LOGIN-CAPTCHA-CHALLENGE-STATE-VERSION-INVALID");
        }
        ChallengeState state = decodeChallenge(encoded);
        if (state == null) {
            throw malformed("challenge", "LOGIN-CAPTCHA-CHALLENGE-STATE-INVALID");
        }
        return ConsumeResult.matched(state);
    }

    @Override
    public void saveProof(String proofHash, ProofState state, int ttlSeconds) {
        bucket(PROOF_KEY_PREFIX + proofHash).set(
            encode(state), Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public ConsumeResult<ProofState> consumeProof(String proofHash, String fingerprint) {
        String encoded = consume(
            CONSUME_PROOF_IF_FINGERPRINT_MATCHES_SCRIPT,
            PROOF_KEY_PREFIX + proofHash,
            fingerprint);
        if (encoded == null) {
            return ConsumeResult.notFound();
        }
        if (FINGERPRINT_MISMATCH.equals(encoded)) {
            return ConsumeResult.fingerprintMismatch();
        }
        ProofState state = decodeProof(encoded);
        if (state == null) {
            throw malformed("proof", "LOGIN-CAPTCHA-PROOF-STATE-INVALID");
        }
        return ConsumeResult.matched(state);
    }

    private String consume(String script, String key, Object... arguments) {
        return redisson.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            script,
            RScript.ReturnType.VALUE,
            List.of(key), arguments);
    }

    private RBucket<String> bucket(String key) {
        return redisson.getBucket(key, StringCodec.INSTANCE);
    }

    private String encode(ChallengeState state) {
        return CHALLENGE_STATE_VERSION + ":" + state.fingerprint() + ":" + state.issuedAtMs()
            + ":" + state.expireAtMs() + ":" + state.targetXNormalized()
            + ":" + state.toleranceNormalized();
    }

    private String encode(ProofState state) {
        return state.fingerprint() + ":" + state.expireAtMs();
    }

    private ChallengeState decodeChallenge(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 6 || !CHALLENGE_STATE_VERSION.equals(parts[0])
            || parts[1].isBlank()) {
            return null;
        }
        try {
            long issuedAtMs = Long.parseLong(parts[2]);
            long expireAtMs = Long.parseLong(parts[3]);
            int targetXNormalized = Integer.parseInt(parts[4]);
            int toleranceNormalized = Integer.parseInt(parts[5]);
            if (issuedAtMs < 0 || expireAtMs <= issuedAtMs
                || targetXNormalized < 0 || targetXNormalized > 1_000
                || toleranceNormalized <= 0 || toleranceNormalized > 1_000) {
                return null;
            }
            return new ChallengeState(parts[1], issuedAtMs, expireAtMs,
                targetXNormalized, toleranceNormalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ProofState decodeProof(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank()) {
            return null;
        }
        try {
            long expireAtMs = Long.parseLong(parts[1]);
            return expireAtMs > 0 ? new ProofState(parts[0], expireAtMs) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private IllegalStateException malformed(String stateType, String errorCode) {
        log.error("login captcha {} state is malformed, code={}", stateType, errorCode);
        return new IllegalStateException("login captcha " + stateType + " state is malformed");
    }
}
