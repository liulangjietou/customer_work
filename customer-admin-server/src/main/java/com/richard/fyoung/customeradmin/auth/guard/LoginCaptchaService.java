package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaChallengeResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaProofResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaProtocol;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaVerifyRequest;
import com.richard.fyoung.customeradmin.auth.dto.SliderTrackPoint;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 登录拼图的 challenge → proof → 登录消费链路。
 *
 * <p>proof 是 32 字节随机数的 Base64URL 表达；存储只接收其 SHA-256 摘要。
 * challenge 与 proof 都绑定来源 IP 和归一化 User-Agent 的摘要，并且只能成功消费一次。</p>
 *
 * <p>challenge 保存随机缺口坐标与服务端容差，客户端只能从图片识别目标；核验同时检查
 * 放置位置、轨迹终点、时序与采样质量。这不是强身份认证，公网入口仍应叠加网关风控。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class LoginCaptchaService {

    private static final String ISSUE_RATE_KEY_PREFIX = "admin:login-captcha:issue:";
    private static final String VERIFY_RATE_KEY_PREFIX = "admin:login-captcha:verify:";
    private static final String PROOF_CONSUME_RATE_KEY_PREFIX = "admin:login-captcha:consume:";
    private static final String UNKNOWN_CLIENT = "unknown";
    private static final int CHALLENGE_ID_BYTES = 16;
    private static final int PROOF_BYTES = 32;
    private static final int MAX_IP_LENGTH = 128;

    private final LoginCaptchaStore store;
    private final LoginCaptchaProperties properties;
    private final WindowCounter counter;
    private final Clock clock;
    private final SecureRandom random;
    private final LoginPuzzleImageGenerator imageGenerator;

    public LoginCaptchaService(LoginCaptchaStore store, LoginCaptchaProperties properties,
                               WindowCounter counter) {
        this(store, properties, counter, Clock.systemUTC(), new SecureRandom());
    }

    LoginCaptchaService(LoginCaptchaStore store, LoginCaptchaProperties properties,
                        WindowCounter counter, Clock clock, SecureRandom random) {
        this(store, properties, counter, clock, random, new LoginPuzzleImageGenerator(random));
    }

    LoginCaptchaService(LoginCaptchaStore store, LoginCaptchaProperties properties,
                        WindowCounter counter, Clock clock, SecureRandom random,
                        LoginPuzzleImageGenerator imageGenerator) {
        this.store = store;
        this.properties = properties;
        this.counter = counter;
        this.clock = clock;
        this.random = random;
        this.imageGenerator = imageGenerator;
    }

    /** 签发独立 challenge；按来源 IP 做滑动窗口限流。 */
    public LoginCaptchaChallengeResponse issueChallenge(String clientIp, String userAgent) {
        String normalizedIp = normalize(clientIp, MAX_IP_LENGTH);
        requireRateLimit(ISSUE_RATE_KEY_PREFIX, normalizedIp, properties.getMaxIssuePerWindow());

        long now = clock.millis();
        long expireAtMs = expireAt(now, properties.getChallengeTtlSeconds());
        String challengeId = randomToken(CHALLENGE_ID_BYTES);
        LoginPuzzleImageGenerator.GeneratedPuzzle puzzle;
        try {
            puzzle = imageGenerator.generate();
        } catch (Exception e) {
            log.error("login puzzle image generation failed, code={}",
                "LOGIN-CAPTCHA-IMAGE-GENERATE-FAIL", e);
            throw new BizException(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE);
        }
        LoginCaptchaStore.ChallengeState state = new LoginCaptchaStore.ChallengeState(
            fingerprint(clientIp, userAgent), now, expireAtMs,
            puzzle.targetXNormalized(), puzzle.toleranceNormalized());
        try {
            store.saveChallenge(challengeId, state, properties.getChallengeTtlSeconds());
        } catch (Exception e) {
            log.error("login captcha challenge persistence failed, code={}",
                "LOGIN-CAPTCHA-CHALLENGE-PERSIST-FAIL", e);
            throw new BizException(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE);
        }
        return puzzle.toResponse(challengeId, properties.getChallengeTtlSeconds());
    }

    /** 原子消费 challenge，轨迹满足约束后签发一次性 proof。 */
    public LoginCaptchaProofResponse verify(LoginCaptchaVerifyRequest request,
                                            String clientIp, String userAgent) {
        if (request == null || !validToken(request.challengeId(), CHALLENGE_ID_BYTES)) {
            throw invalid();
        }
        requireRateLimit(VERIFY_RATE_KEY_PREFIX, normalize(clientIp, MAX_IP_LENGTH),
            properties.getMaxVerifyPerWindow());
        String fingerprint = fingerprint(clientIp, userAgent);
        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ChallengeState> consumed;
        try {
            consumed = store.consumeChallenge(request.challengeId().trim(), fingerprint);
        } catch (Exception e) {
            log.error("login captcha challenge consume failed, code={}",
                "LOGIN-CAPTCHA-CHALLENGE-CONSUME-FAIL", e);
            throw new BizException(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE);
        }
        if (consumed.status() != LoginCaptchaStore.ConsumeStatus.MATCHED || consumed.state() == null) {
            throw invalid();
        }

        long now = clock.millis();
        LoginCaptchaStore.ChallengeState challenge = consumed.state();
        if (!validChallengeState(challenge, now)
            || now - challenge.issuedAtMs() < LoginCaptchaProtocol.MIN_DURATION_MS
            || !validPlacement(request.placementX(), challenge)
            || !validTrajectory(request.trajectory(), request.placementX())) {
            throw invalid();
        }

        String proof = randomToken(PROOF_BYTES);
        long expireAtMs = expireAt(now, properties.getProofTtlSeconds());
        try {
            // 键只使用 proof 摘要；proof 明文仅返回给当前调用方。
            store.saveProof(sha256(proof), new LoginCaptchaStore.ProofState(fingerprint, expireAtMs),
                properties.getProofTtlSeconds());
        } catch (Exception e) {
            log.error("login captcha proof persistence failed, code={}",
                "LOGIN-CAPTCHA-PROOF-PERSIST-FAIL", e);
            throw new BizException(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE);
        }
        return new LoginCaptchaProofResponse(proof, properties.getProofTtlSeconds());
    }

    /** 在进入用户查询、BCrypt 或 LDAP Bind 前消费 proof。 */
    public void consumeProof(String proof, String clientIp, String userAgent) {
        if (!validToken(proof, PROOF_BYTES)) {
            throw invalid();
        }
        requireRateLimit(PROOF_CONSUME_RATE_KEY_PREFIX, normalize(clientIp, MAX_IP_LENGTH),
            properties.getMaxProofConsumePerWindow());
        LoginCaptchaStore.ConsumeResult<LoginCaptchaStore.ProofState> consumed;
        try {
            consumed = store.consumeProof(sha256(proof.trim()), fingerprint(clientIp, userAgent));
        } catch (Exception e) {
            log.error("login captcha proof consume failed, code={}",
                "LOGIN-CAPTCHA-PROOF-CONSUME-FAIL", e);
            throw new BizException(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE);
        }
        if (consumed.status() != LoginCaptchaStore.ConsumeStatus.MATCHED
            || consumed.state() == null
            || consumed.state().expireAtMs() <= clock.millis()) {
            throw invalid();
        }
    }

    private boolean validChallengeState(LoginCaptchaStore.ChallengeState challenge, long now) {
        return challenge.issuedAtMs() >= 0
            && challenge.issuedAtMs() <= now
            && challenge.expireAtMs() > now
            && challenge.expireAtMs() > challenge.issuedAtMs()
            && challenge.targetXNormalized() >= LoginCaptchaProtocol.TRACK_MIN_X
            && challenge.targetXNormalized() <= LoginCaptchaProtocol.TRACK_MAX_X
            && challenge.toleranceNormalized() > 0
            && challenge.toleranceNormalized() <= LoginCaptchaProtocol.TRACK_MAX_X;
    }

    private boolean validPlacement(Integer placementX, LoginCaptchaStore.ChallengeState challenge) {
        return placementX != null
            && placementX >= LoginCaptchaProtocol.TRACK_MIN_X
            && placementX <= LoginCaptchaProtocol.TRACK_MAX_X
            && Math.abs(placementX - challenge.targetXNormalized()) <= challenge.toleranceNormalized();
    }

    private boolean validTrajectory(List<SliderTrackPoint> points, Integer placementX) {
        if (points == null || points.size() < LoginCaptchaProtocol.MIN_POINTS
            || points.size() > LoginCaptchaProtocol.MAX_POINTS
            || placementX == null) {
            return false;
        }
        SliderTrackPoint first = points.get(0);
        SliderTrackPoint last = points.get(points.size() - 1);
        if (first == null || last == null
            || first.x() > LoginCaptchaProtocol.START_X_MAX
            || first.t() < 0 || first.t() > LoginCaptchaProtocol.MAX_INITIAL_TIME_MS
            || Math.abs(last.x() - placementX) > LoginCaptchaProtocol.ENDPOINT_PLACEMENT_TOLERANCE
            || last.t() < LoginCaptchaProtocol.MIN_DURATION_MS
            || last.t() > LoginCaptchaProtocol.MAX_DURATION_MS) {
            return false;
        }

        Set<Integer> distinctX = new HashSet<>();
        int intermediatePoints = 0;
        long previousTime = -1;
        for (int index = 0; index < points.size(); index++) {
            SliderTrackPoint point = points.get(index);
            if (point == null || point.x() < LoginCaptchaProtocol.TRACK_MIN_X
                || point.x() > LoginCaptchaProtocol.TRACK_MAX_X
                || point.y() < LoginCaptchaProtocol.TRACK_MIN_Y
                || point.y() > LoginCaptchaProtocol.TRACK_MAX_Y
                || point.t() < 0 || point.t() > LoginCaptchaProtocol.MAX_DURATION_MS
                || point.t() <= previousTime) {
                return false;
            }
            previousTime = point.t();
            distinctX.add(point.x());
            if (index > 0 && index < points.size() - 1
                && point.x() > LoginCaptchaProtocol.START_X_MAX
                && point.x() < placementX - LoginCaptchaProtocol.ENDPOINT_PLACEMENT_TOLERANCE) {
                intermediatePoints++;
            }
        }
        return distinctX.size() >= LoginCaptchaProtocol.MIN_DISTINCT_X
            && intermediatePoints >= LoginCaptchaProtocol.MIN_INTERMEDIATE_POINTS;
    }

    private String fingerprint(String clientIp, String userAgent) {
        String normalizedIp = normalize(clientIp, MAX_IP_LENGTH);
        String normalizedUserAgent = normalize(userAgent, properties.getMaxUserAgentLength());
        return sha256(normalizedIp + '\0' + normalizedUserAgent);
    }

    private void requireRateLimit(String keyPrefix, String normalizedIp, int limit) {
        boolean allowed = counter.tryAcquireSliding(
            keyPrefix + sha256(normalizedIp), limit, properties.getRateLimitWindowSeconds());
        if (!allowed) {
            throw new BizException(ResultCode.LOGIN_CAPTCHA_TOO_FREQUENT);
        }
    }

    /** 归一化空白并截断，避免同一 UA 因多余空格漂移，也限制进入摘要函数的输入规模。 */
    private String normalize(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN_CLIENT;
        }
        StringBuilder normalized = new StringBuilder(Math.min(raw.length(), maxLength));
        boolean previousWhitespace = false;
        for (int i = 0; i < raw.length() && normalized.length() < maxLength; i++) {
            char current = raw.charAt(i);
            if (Character.isWhitespace(current)) {
                if (normalized.length() > 0 && !previousWhitespace) {
                    normalized.append(' ');
                }
                previousWhitespace = true;
            } else {
                normalized.append(current);
                previousWhitespace = false;
            }
        }
        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') {
            normalized.deleteCharAt(length - 1);
        }
        return normalized.isEmpty() ? UNKNOWN_CLIENT : normalized.toString();
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private boolean validToken(String token, int bytes) {
        if (token == null) {
            return false;
        }
        String trimmed = token.trim();
        int expectedLength = (bytes * 8 + 5) / 6;
        if (trimmed.length() != expectedLength) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            boolean alphaNumeric = current >= 'a' && current <= 'z'
                || current >= 'A' && current <= 'Z'
                || current >= '0' && current <= '9';
            if (!alphaNumeric && current != '-' && current != '_') {
                return false;
            }
        }
        return true;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private long expireAt(long now, int ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new BizException(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE);
        }
        try {
            return Math.addExact(now, Math.multiplyExact((long) ttlSeconds, 1000L));
        } catch (ArithmeticException e) {
            throw new BizException(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE);
        }
    }

    private BizException invalid() {
        return new BizException(ResultCode.LOGIN_CAPTCHA_INVALID);
    }
}
