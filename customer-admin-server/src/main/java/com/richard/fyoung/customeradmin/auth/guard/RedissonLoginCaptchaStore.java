package com.richard.fyoung.customeradmin.auth.guard;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;

/**
 * 登录滑块的 Redis 存储。
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
    private static final String FINGERPRINT_MISMATCH = "__FINGERPRINT_MISMATCH__";

    private static final String CONSUME_IF_FINGERPRINT_MATCHES_SCRIPT =
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
        String encoded = consume(CHALLENGE_KEY_PREFIX + challengeId, fingerprint);
        if (encoded == null) {
            return ConsumeResult.notFound();
        }
        if (FINGERPRINT_MISMATCH.equals(encoded)) {
            return ConsumeResult.fingerprintMismatch();
        }
        ChallengeState state = decodeChallenge(encoded);
        if (state == null) {
            log.error("login captcha challenge state is malformed, code={}",
                "LOGIN-CAPTCHA-CHALLENGE-STATE-INVALID");
            throw new IllegalStateException("login captcha challenge state is malformed");
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
        String encoded = consume(PROOF_KEY_PREFIX + proofHash, fingerprint);
        if (encoded == null) {
            return ConsumeResult.notFound();
        }
        if (FINGERPRINT_MISMATCH.equals(encoded)) {
            return ConsumeResult.fingerprintMismatch();
        }
        ProofState state = decodeProof(encoded);
        if (state == null) {
            log.error("login captcha proof state is malformed, code={}",
                "LOGIN-CAPTCHA-PROOF-STATE-INVALID");
            throw new IllegalStateException("login captcha proof state is malformed");
        }
        return ConsumeResult.matched(state);
    }

    private String consume(String key, String fingerprint) {
        return redisson.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            CONSUME_IF_FINGERPRINT_MATCHES_SCRIPT,
            RScript.ReturnType.VALUE,
            List.of(key), fingerprint);
    }

    private RBucket<String> bucket(String key) {
        return redisson.getBucket(key, StringCodec.INSTANCE);
    }

    private String encode(ChallengeState state) {
        return state.fingerprint() + ":" + state.issuedAtMs() + ":" + state.expireAtMs();
    }

    private String encode(ProofState state) {
        return state.fingerprint() + ":" + state.expireAtMs();
    }

    private ChallengeState decodeChallenge(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new ChallengeState(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ProofState decodeProof(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new ProofState(parts[0], Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
