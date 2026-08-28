package com.richard.fyoung.customeradmin.auth.guard;

/**
 * 登录滑块 challenge/proof 的一次性存储。
 *
 * <p>实现必须保证：指纹匹配时读取与删除是同一个原子操作；指纹不匹配时不删除，
 * 避免第三方仅凭 challengeId 或 proof 摘要让真实用户的凭据失效。分布式实现的任何
 * 读写异常必须向上抛，由服务层 fail-closed；请求期不得切换到另一份存储。</p>
 * @author owlzhangfq@gmail.com
 */
public interface LoginCaptchaStore {

    void saveChallenge(String challengeId, ChallengeState state, int ttlSeconds);

    ConsumeResult<ChallengeState> consumeChallenge(String challengeId, String fingerprint);

    /** @param proofHash proof 明文的 SHA-256 十六进制摘要，存储层不得接收 proof 明文。 */
    void saveProof(String proofHash, ProofState state, int ttlSeconds);

    /** @param proofHash proof 明文的 SHA-256 十六进制摘要。 */
    ConsumeResult<ProofState> consumeProof(String proofHash, String fingerprint);

    record ChallengeState(String fingerprint, long issuedAtMs, long expireAtMs) {
    }

    record ProofState(String fingerprint, long expireAtMs) {
    }

    enum ConsumeStatus {
        MATCHED,
        NOT_FOUND,
        FINGERPRINT_MISMATCH
    }

    record ConsumeResult<T>(ConsumeStatus status, T state) {

        public static <T> ConsumeResult<T> matched(T state) {
            return new ConsumeResult<>(ConsumeStatus.MATCHED, state);
        }

        public static <T> ConsumeResult<T> notFound() {
            return new ConsumeResult<>(ConsumeStatus.NOT_FOUND, null);
        }

        public static <T> ConsumeResult<T> fingerprintMismatch() {
            return new ConsumeResult<>(ConsumeStatus.FINGERPRINT_MISMATCH, null);
        }
    }
}
