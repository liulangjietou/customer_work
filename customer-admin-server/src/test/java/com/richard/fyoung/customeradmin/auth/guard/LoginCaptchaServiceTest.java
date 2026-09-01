package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaChallengeResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaProofResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaProtocol;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaVerifyRequest;
import com.richard.fyoung.customeradmin.auth.dto.SliderTrackPoint;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 登录拼图从 challenge 到 proof 的安全边界。 */
class LoginCaptchaServiceTest {

    private static final String IP = "203.0.113.31";
    private static final String USER_AGENT = "Mozilla/5.0 customer-admin-test";
    private static final int TARGET_X = 620;
    private static final int TARGET_TOLERANCE = 12;

    private MutableClock clock;
    private LoginCaptchaProperties properties;
    private WindowCounter counter;
    private LoginPuzzleImageGenerator imageGenerator;
    private LoginCaptchaService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(10_000L);
        properties = new LoginCaptchaProperties();
        counter = mock(WindowCounter.class);
        when(counter.tryAcquireSliding(anyString(), anyInt(), anyInt())).thenReturn(true);
        imageGenerator = mock(LoginPuzzleImageGenerator.class);
        when(imageGenerator.generate()).thenReturn(generatedPuzzle());
        service = new LoginCaptchaService(new InMemoryLoginCaptchaStore(100, clock), properties,
            counter, clock, new SecureRandom(), imageGenerator);
    }

    @Test
    void issueChallenge_shouldReturnPuzzleAssetsAndPersistOnlyServerSecret() {
        LoginCaptchaStore store = mock(LoginCaptchaStore.class);
        LoginCaptchaService localService = serviceWith(store);

        LoginCaptchaChallengeResponse challenge = localService.issueChallenge(IP, USER_AGENT);

        assertEquals("data:image/png;base64,YmFja2dyb3VuZA==", challenge.backgroundImage());
        assertEquals("data:image/png;base64,cGllY2U=", challenge.puzzlePieceImage());
        assertEquals(320, challenge.canvasWidth());
        assertEquals(160, challenge.canvasHeight());
        assertEquals(56, challenge.pieceWidth());
        assertEquals(56, challenge.pieceHeight());
        assertEquals(52, challenge.pieceY());
        org.mockito.ArgumentCaptor<LoginCaptchaStore.ChallengeState> state =
            org.mockito.ArgumentCaptor.forClass(LoginCaptchaStore.ChallengeState.class);
        verify(store).saveChallenge(eq(challenge.challengeId()), state.capture(),
            eq(properties.getChallengeTtlSeconds()));
        assertEquals(TARGET_X, state.getValue().targetXNormalized());
        assertEquals(TARGET_TOLERANCE, state.getValue().toleranceNormalized());
    }

    @Test
    void verify_shouldIssueProofForValidTrajectory() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        LoginCaptchaProofResponse proof = service.verify(request(challenge, validTrajectory()), IP, USER_AGENT);

        assertNotNull(proof.proof());
        assertFalse(proof.proof().isBlank());
        assertEquals(properties.getProofTtlSeconds(), proof.ttlSeconds());
        assertDoesNotThrow(() -> service.consumeProof(proof.proof(), IP, USER_AGENT));
    }

    @Test
    void verify_shouldIssue32ByteBase64UrlProofAndStoreOnlyItsSha256() throws Exception {
        InMemoryLoginCaptchaStore store = spy(new InMemoryLoginCaptchaStore(100, clock));
        LoginCaptchaService localService = new LoginCaptchaService(
            store, properties, counter, clock, new SecureRandom(), imageGenerator);
        LoginCaptchaChallengeResponse challenge = localService.issueChallenge(IP, USER_AGENT);
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        LoginCaptchaProofResponse proof = localService.verify(
            request(challenge, validTrajectory()), IP, USER_AGENT);

        assertEquals(32, Base64.getUrlDecoder().decode(proof.proof()).length);
        org.mockito.ArgumentCaptor<String> proofKey = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).saveProof(proofKey.capture(), any(), eq(properties.getProofTtlSeconds()));
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(proof.proof().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expectedHash, proofKey.getValue());
        assertNotEquals(proof.proof(), proofKey.getValue());
    }

    @Test
    void fingerprint_shouldNormalizeWhitespaceAndIgnoreUserAgentBeyondConfiguredLimit() {
        properties.setMaxUserAgentLength(12);
        LoginCaptchaChallengeResponse challenge = service.issueChallenge(IP, " Agent/1.0   suffix-a");
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        assertNotNull(service.verify(request(challenge, validTrajectory()), IP, "Agent/1.0 suffix-b"));
    }

    @Test
    void verify_shouldRejectTooFastAndConsumeChallenge() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS - 1);

        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldRejectTooFewPointsAndConsumeChallenge() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        assertInvalid(() -> service.verify(request(challenge, validTrajectory().subList(0, 5)), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldRejectTeleportTrackAndConsumeChallenge() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        List<SliderTrackPoint> teleport = List.of(
            point(0, 0), point(0, 80), point(0, 160), point(0, 240), point(0, 320),
            point(TARGET_X, 400));

        assertInvalid(() -> service.verify(request(challenge, teleport), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldRejectTrackThatDoesNotReachEndAndConsumeChallenge() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        List<SliderTrackPoint> incomplete = new ArrayList<>(validTrajectory());
        incomplete.set(incomplete.size() - 1,
            point(TARGET_X - LoginCaptchaProtocol.ENDPOINT_PLACEMENT_TOLERANCE - 1, 400));

        assertInvalid(() -> service.verify(request(challenge, incomplete), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldRejectNonIncreasingTimeAndConsumeChallenge() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        List<SliderTrackPoint> nonIncreasing = new ArrayList<>(validTrajectory());
        nonIncreasing.set(3, point(520, 120));

        assertInvalid(() -> service.verify(request(challenge, nonIncreasing), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldRejectTrackWithInsufficientIntermediatePoints() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        List<SliderTrackPoint> sparse = List.of(
            point(0, 0), point(10, 80), point(12, 160), point(14, 240), point(16, 320),
            point(TARGET_X, 400));

        assertInvalid(() -> service.verify(request(challenge, sparse), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("independentInvalidTrajectoryCases")
    void verify_shouldRejectEveryIndependentTrajectoryBoundaryAndConsumeChallenge(
        String caseName, List<SliderTrackPoint> trajectory) {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        assertInvalid(() -> service.verify(request(challenge, trajectory), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldKeepChallengeWhenIpDoesNotMatch() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), "198.51.100.42", USER_AGENT));

        assertNotNull(service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldKeepChallengeWhenUserAgentDoesNotMatch() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, "other-agent"));

        assertNotNull(service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldRejectPlacementOutsideServerTargetToleranceAndConsumeChallenge() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        int wrongPlacement = TARGET_X + TARGET_TOLERANCE + 1;

        assertInvalid(() -> service.verify(
            request(challenge, wrongPlacement, validTrajectory(wrongPlacement)), IP, USER_AGENT));
        assertInvalid(() -> service.verify(request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldAcceptPlacementAtServerToleranceBoundary() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        int boundaryPlacement = TARGET_X + TARGET_TOLERANCE;

        assertNotNull(service.verify(
            request(challenge, boundaryPlacement, validTrajectory(boundaryPlacement)), IP, USER_AGENT));
    }

    @Test
    void verify_shouldRejectTrajectoryEndpointThatDiffersFromPlacement() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        List<SliderTrackPoint> mismatched = new ArrayList<>(validTrajectory());
        mismatched.set(mismatched.size() - 1,
            point(TARGET_X - LoginCaptchaProtocol.ENDPOINT_PLACEMENT_TOLERANCE - 1, 400));

        assertInvalid(() -> service.verify(
            request(challenge, TARGET_X, mismatched), IP, USER_AGENT));
    }

    @Test
    void consumeProof_shouldBeOneTimeAndKeepProofOnFingerprintMismatch() {
        LoginCaptchaProofResponse proof = verifiedProof();

        assertInvalid(() -> service.consumeProof(proof.proof(), "198.51.100.42", USER_AGENT));
        assertDoesNotThrow(() -> service.consumeProof(proof.proof(), IP, USER_AGENT));
        assertInvalid(() -> service.consumeProof(proof.proof(), IP, USER_AGENT));
    }

    @Test
    void consumeProof_shouldRejectExpiredProof() {
        properties.setProofTtlSeconds(1);
        LoginCaptchaProofResponse proof = verifiedProof();
        clock.advance(1_001L);

        assertInvalid(() -> service.consumeProof(proof.proof(), IP, USER_AGENT));
    }

    @Test
    void issueChallenge_shouldMapStoreSaveFailureToUnavailable() {
        LoginCaptchaStore failingStore = mock(LoginCaptchaStore.class);
        doThrow(new IllegalStateException("redis timeout")).when(failingStore)
            .saveChallenge(anyString(), any(), anyInt());

        assertUnavailable(() -> serviceWith(failingStore).issueChallenge(IP, USER_AGENT));
    }

    @Test
    void issueChallenge_shouldMapImageGenerationFailureToUnavailableWithoutPersisting() {
        LoginCaptchaStore store = mock(LoginCaptchaStore.class);
        when(imageGenerator.generate()).thenThrow(new IllegalStateException("png unavailable"));

        assertUnavailable(() -> serviceWith(store).issueChallenge(IP, USER_AGENT));

        verify(store, never()).saveChallenge(anyString(), any(), anyInt());
    }

    @Test
    void verify_shouldMapChallengeConsumeFailureToUnavailable() {
        LoginCaptchaStore failingStore = mock(LoginCaptchaStore.class);
        LoginCaptchaService failingService = serviceWith(failingStore);
        LoginCaptchaChallengeResponse challenge = failingService.issueChallenge(IP, USER_AGENT);
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        when(failingStore.consumeChallenge(eq(challenge.challengeId()), anyString()))
            .thenThrow(new IllegalStateException("redis timeout"));

        assertUnavailable(() -> failingService.verify(
            request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void verify_shouldMapProofSaveFailureToUnavailable() {
        LoginCaptchaStore failingStore = mock(LoginCaptchaStore.class);
        LoginCaptchaService failingService = serviceWith(failingStore);
        long issuedAt = clock.millis();
        LoginCaptchaChallengeResponse challenge = failingService.issueChallenge(IP, USER_AGENT);
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        when(failingStore.consumeChallenge(eq(challenge.challengeId()), anyString()))
            .thenReturn(LoginCaptchaStore.ConsumeResult.matched(
                new LoginCaptchaStore.ChallengeState(
                    "fingerprint", issuedAt, clock.millis() + 10_000L,
                    TARGET_X, TARGET_TOLERANCE)));
        doThrow(new IllegalStateException("redis timeout")).when(failingStore)
            .saveProof(anyString(), any(), anyInt());

        assertUnavailable(() -> failingService.verify(
            request(challenge, validTrajectory()), IP, USER_AGENT));
    }

    @Test
    void consumeProof_shouldMapStoreFailureToUnavailable() {
        LoginCaptchaStore failingStore = mock(LoginCaptchaStore.class);
        LoginCaptchaService failingService = serviceWith(failingStore);
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        when(failingStore.consumeProof(anyString(), anyString()))
            .thenThrow(new IllegalStateException("redis timeout"));

        assertUnavailable(() -> failingService.consumeProof(proof, IP, USER_AGENT));
    }

    @Test
    void issueChallenge_shouldEnforcePerIpRateLimit() {
        properties.setMaxIssuePerWindow(2);
        when(counter.tryAcquireSliding(anyString(), anyInt(), anyInt())).thenReturn(true, true, false);

        assertNotNull(issue());
        assertNotNull(issue());
        BizException error = assertThrows(BizException.class, () -> issue());

        assertEquals(ResultCode.LOGIN_CAPTCHA_TOO_FREQUENT, error.getResultCode());
        verify(counter, times(3)).tryAcquireSliding(anyString(), anyInt(), anyInt());
    }

    @Test
    void issueChallenge_shouldUseNormalizedPerIpRateKeyAndConfiguredWindow() throws Exception {
        service.issueChallenge("  " + IP + "  ", USER_AGENT);
        service.issueChallenge(IP, USER_AGENT);
        service.issueChallenge("198.51.100.42", USER_AGENT);

        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(counter, times(3)).tryAcquireSliding(keys.capture(),
            eq(properties.getMaxIssuePerWindow()), eq(properties.getRateLimitWindowSeconds()));
        List<String> captured = keys.getAllValues();
        assertEquals(captured.get(0), captured.get(1), "同一 IP 归一化后应共享限流键");
        assertNotEquals(captured.get(1), captured.get(2), "不同 IP 不能共享限流键");
        assertEquals("admin:login-captcha:issue:" + sha256(IP), captured.get(0));
    }

    @Test
    void issueChallenge_shouldNotPersistWhenRateLimitRejects() {
        LoginCaptchaStore store = mock(LoginCaptchaStore.class);
        when(counter.tryAcquireSliding(anyString(), anyInt(), anyInt())).thenReturn(false);

        BizException error = assertThrows(BizException.class,
            () -> serviceWith(store).issueChallenge(IP, USER_AGENT));

        assertEquals(ResultCode.LOGIN_CAPTCHA_TOO_FREQUENT, error.getResultCode());
        verify(store, never()).saveChallenge(anyString(), any(), anyInt());
    }

    @Test
    void verify_shouldRejectRateLimitBeforeAccessingStore() throws Exception {
        LoginCaptchaStore store = mock(LoginCaptchaStore.class);
        properties.setMaxVerifyPerWindow(7);
        when(counter.tryAcquireSliding(anyString(), anyInt(), anyInt())).thenReturn(false);
        LoginCaptchaVerifyRequest request = new LoginCaptchaVerifyRequest(
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]),
            TARGET_X, validTrajectory());

        BizException error = assertThrows(BizException.class,
            () -> serviceWith(store).verify(request, IP, USER_AGENT));

        assertEquals(ResultCode.LOGIN_CAPTCHA_TOO_FREQUENT, error.getResultCode());
        verify(store, never()).consumeChallenge(anyString(), anyString());
        verify(counter).tryAcquireSliding(
            eq("admin:login-captcha:verify:" + sha256(IP)),
            eq(7), eq(properties.getRateLimitWindowSeconds()));
    }

    @Test
    void verify_shouldAllowOnlyThreeAttemptsPerHourAcrossSuccessFailureAndUserAgentChanges() {
        InMemoryLoginCaptchaStore store = spy(new InMemoryLoginCaptchaStore(100, clock));
        LoginCaptchaService localService = new LoginCaptchaService(
            store, properties, counter, clock, new SecureRandom(), imageGenerator);
        when(counter.tryAcquireSliding(
            org.mockito.ArgumentMatchers.startsWith("admin:login-captcha:verify:"),
            eq(3), eq(3_600)))
            .thenReturn(true, true, true, false);
        List<LoginCaptchaChallengeResponse> challenges = List.of(
            localService.issueChallenge(IP, USER_AGENT),
            localService.issueChallenge(IP, USER_AGENT),
            localService.issueChallenge(IP, USER_AGENT),
            localService.issueChallenge(IP, USER_AGENT));
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);

        assertNotNull(localService.verify(
            request(challenges.get(0), validTrajectory()), IP, USER_AGENT));
        int wrongPlacement = TARGET_X + TARGET_TOLERANCE + 1;
        assertInvalid(() -> localService.verify(
            request(challenges.get(1), wrongPlacement, validTrajectory(wrongPlacement)),
            IP, USER_AGENT));
        assertInvalid(() -> localService.verify(
            request(challenges.get(2), validTrajectory()), IP, "rotated-user-agent"));

        BizException exhausted = assertThrows(BizException.class, () -> localService.verify(
            request(challenges.get(3), validTrajectory()), IP, USER_AGENT));
        assertEquals(ResultCode.LOGIN_CAPTCHA_TOO_FREQUENT, exhausted.getResultCode());
        verify(counter, times(4)).tryAcquireSliding(
            org.mockito.ArgumentMatchers.startsWith("admin:login-captcha:verify:"),
            eq(3), eq(3_600));
        verify(store, times(3)).consumeChallenge(anyString(), anyString());
    }

    @Test
    void consumeProof_shouldRejectRateLimitBeforeAccessingStore() throws Exception {
        LoginCaptchaStore store = mock(LoginCaptchaStore.class);
        properties.setMaxProofConsumePerWindow(11);
        when(counter.tryAcquireSliding(anyString(), anyInt(), anyInt())).thenReturn(false);
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        BizException error = assertThrows(BizException.class,
            () -> serviceWith(store).consumeProof(proof, IP, USER_AGENT));

        assertEquals(ResultCode.LOGIN_CAPTCHA_TOO_FREQUENT, error.getResultCode());
        verify(store, never()).consumeProof(anyString(), anyString());
        verify(counter).tryAcquireSliding(
            eq("admin:login-captcha:consume:" + sha256(IP)),
            eq(11), eq(properties.getRateLimitWindowSeconds()));
    }

    private LoginCaptchaChallengeResponse issue() {
        return service.issueChallenge(IP, USER_AGENT);
    }

    private LoginCaptchaService serviceWith(LoginCaptchaStore store) {
        return new LoginCaptchaService(
            store, properties, counter, clock, new SecureRandom(), imageGenerator);
    }

    private LoginCaptchaProofResponse verifiedProof() {
        LoginCaptchaChallengeResponse challenge = issue();
        clock.advance(LoginCaptchaProtocol.MIN_DURATION_MS);
        return service.verify(request(challenge, validTrajectory()), IP, USER_AGENT);
    }

    private LoginCaptchaVerifyRequest request(LoginCaptchaChallengeResponse challenge,
                                              List<SliderTrackPoint> trajectory) {
        return request(challenge, TARGET_X, trajectory);
    }

    private LoginCaptchaVerifyRequest request(LoginCaptchaChallengeResponse challenge,
                                              int placementX,
                                              List<SliderTrackPoint> trajectory) {
        return new LoginCaptchaVerifyRequest(challenge.challengeId(), placementX, trajectory);
    }

    private List<SliderTrackPoint> validTrajectory() {
        return validTrajectory(TARGET_X);
    }

    private List<SliderTrackPoint> validTrajectory(int placementX) {
        return List.of(point(0, 0), point(placementX * 16 / 100, 50),
            point(placementX * 35 / 100, 120), point(placementX * 55 / 100, 210),
            point(placementX * 78 / 100, 320), point(placementX, 400));
    }

    private SliderTrackPoint point(int x, long timeMs) {
        return new SliderTrackPoint(x, 0, timeMs);
    }

    private static Stream<Arguments> independentInvalidTrajectoryCases() {
        List<SliderTrackPoint> startTooFar = mutableValidTrajectory();
        startTooFar.set(0, trackPoint(21, 0, 0));

        List<SliderTrackPoint> initialPointTooLate = mutableValidTrajectory();
        initialPointTooLate.set(0, trackPoint(0, 0, 101));
        initialPointTooLate.set(1, trackPoint(120, 0, 150));

        List<SliderTrackPoint> durationTooShort = mutableValidTrajectory();
        durationTooShort.set(1, trackPoint(100, 0, 40));
        durationTooShort.set(2, trackPoint(220, 0, 90));
        durationTooShort.set(3, trackPoint(340, 0, 150));
        durationTooShort.set(4, trackPoint(480, 0, 220));
        durationTooShort.set(5, trackPoint(TARGET_X, 0, 299));

        List<SliderTrackPoint> durationTooLong = mutableValidTrajectory();
        durationTooLong.set(5, trackPoint(TARGET_X, 0, 8_001));

        List<SliderTrackPoint> tooManyPoints = IntStream.rangeClosed(0, 80)
            .mapToObj(index -> trackPoint(index * TARGET_X / 80, 0, index * 10L))
            .toList();

        List<SliderTrackPoint> yTooLarge = mutableValidTrajectory();
        yTooLarge.set(3, trackPoint(520, 1_001, 210));

        List<SliderTrackPoint> nullIntermediate = new ArrayList<>(
            Arrays.asList(trackPoint(0, 0, 0), trackPoint(100, 0, 50), null,
                trackPoint(340, 0, 210), trackPoint(480, 0, 320), trackPoint(TARGET_X, 0, 400)));

        return Stream.of(
            Arguments.of("起点超过 20", startTooFar),
            Arguments.of("首点晚于 100ms", initialPointTooLate),
            Arguments.of("轨迹短于 300ms", durationTooShort),
            Arguments.of("轨迹长于 8000ms", durationTooLong),
            Arguments.of("轨迹超过 80 点", tooManyPoints),
            Arguments.of("Y 偏移超过 1000", yTooLarge),
            Arguments.of("中间采样点为空", nullIntermediate));
    }

    private static List<SliderTrackPoint> mutableValidTrajectory() {
        return new ArrayList<>(List.of(trackPoint(0, 0, 0), trackPoint(100, 0, 50),
            trackPoint(220, 0, 120), trackPoint(340, 0, 210), trackPoint(480, 0, 320),
            trackPoint(TARGET_X, 0, 400)));
    }

    private LoginPuzzleImageGenerator.GeneratedPuzzle generatedPuzzle() {
        return new LoginPuzzleImageGenerator.GeneratedPuzzle(
            "data:image/png;base64,YmFja2dyb3VuZA==",
            "data:image/png;base64,cGllY2U=",
            320, 160, 56, 56, 52, TARGET_X, TARGET_TOLERANCE);
    }

    private static SliderTrackPoint trackPoint(int x, int y, long timeMs) {
        return new SliderTrackPoint(x, y, timeMs);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void assertInvalid(ThrowingRunnable action) {
        BizException error = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.LOGIN_CAPTCHA_INVALID, error.getResultCode());
    }

    private void assertUnavailable(ThrowingRunnable action) {
        BizException error = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.LOGIN_CAPTCHA_UNAVAILABLE, error.getResultCode());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static final class MutableClock extends Clock {
        private long currentMillis;

        private MutableClock(long currentMillis) {
            this.currentMillis = currentMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }

        @Override
        public long millis() {
            return currentMillis;
        }

        private void advance(long millis) {
            currentMillis += millis;
        }
    }
}
