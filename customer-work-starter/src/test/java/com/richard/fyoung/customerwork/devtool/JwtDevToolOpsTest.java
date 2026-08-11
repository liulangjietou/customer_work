package com.richard.fyoung.customerwork.devtool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtDevToolOps} 单测：解码、标准声明解读、有效期判定与 HS* 签名校验。
 *
 * <p>用的是 jwt.io 首页那条公开示例令牌（HS256，密钥 your-256-bit-secret），不涉及任何真实凭据。</p>
 * @author owlzhangfq@gmail.com
 */
class JwtDevToolOpsTest {

    /** jwt.io 公开示例：{"sub":"1234567890","name":"John Doe","iat":1516239022}。 */
    private static final String SAMPLE_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ"
        + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    private static final String SAMPLE_SECRET = "your-256-bit-secret";

    private final JwtDevToolOps ops = new JwtDevToolOps();

    @Test
    void decode_shouldExposeHeaderAndClaims() {
        JwtDevToolOps.JwtDecodeResult result = ops.decode(SAMPLE_TOKEN, null, null);
        assertEquals("HS256", result.getAlgorithm());
        assertEquals("JWT", result.getType());
        assertEquals("1234567890", result.getSubject());
        assertTrue(result.getPayload().contains("John Doe"));
        assertEquals("2018-01-18 09:30:22", result.getIssuedAt(), "iat 应按北京时间格式化");
        assertFalse(result.isUnsigned());
    }

    /** 没有 exp 声明时不应武断判成已过期。 */
    @Test
    void decode_shouldNotMarkExpired_whenNoExpClaim() {
        JwtDevToolOps.JwtDecodeResult result = ops.decode(SAMPLE_TOKEN, null, null);
        assertFalse(result.isExpired());
        assertNull(result.getExpiresAt());
        assertNull(result.getSecondsRemaining());
    }

    @Test
    void decode_shouldNotCheckSignature_whenSecretMissing() {
        assertEquals("NOT_CHECKED", ops.decode(SAMPLE_TOKEN, null, null).getSignatureStatus());
    }

    @Test
    void decode_shouldVerifyHmacSignature() {
        assertEquals("VALID", ops.decode(SAMPLE_TOKEN, SAMPLE_SECRET, null).getSignatureStatus());
        assertEquals("INVALID", ops.decode(SAMPLE_TOKEN, "wrong-secret", null).getSignatureStatus());
    }

    @Test
    void decode_shouldSupportSecretEncodings() {
        String hexSecret = toHex(SAMPLE_SECRET.getBytes(StandardCharsets.UTF_8));
        String base64Secret = Base64.getEncoder().encodeToString(SAMPLE_SECRET.getBytes(StandardCharsets.UTF_8));
        assertEquals("VALID", ops.decode(SAMPLE_TOKEN, hexSecret, "hex").getSignatureStatus());
        assertEquals("VALID", ops.decode(SAMPLE_TOKEN, base64Secret, "base64").getSignatureStatus());
    }

    /** 非 HS* 算法只能解码，绝不能因为"给了密钥"就报成校验通过。 */
    @Test
    void decode_shouldReportUnsupportedAlg_forAsymmetric() {
        String token = buildToken("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", "{\"sub\":\"x\"}", "fake-signature");
        assertEquals("UNSUPPORTED_ALG", ops.decode(token, "any-secret", null).getSignatureStatus());
    }

    @Test
    void decode_shouldFlagExpiredToken() {
        long past = Instant.now().getEpochSecond() - 3600;
        String token = buildToken("{\"alg\":\"HS256\"}", "{\"exp\":" + past + "}", "sig");
        JwtDevToolOps.JwtDecodeResult result = ops.decode(token, null, null);
        assertTrue(result.isExpired());
        assertNotNull(result.getExpiresAt());
        assertTrue(result.getSecondsRemaining() < 0, "已过期时剩余秒数应为负");
    }

    @Test
    void decode_shouldFlagNotYetValidToken() {
        long future = Instant.now().getEpochSecond() + 3600;
        String token = buildToken("{\"alg\":\"HS256\"}", "{\"nbf\":" + future + "}", "sig");
        JwtDevToolOps.JwtDecodeResult result = ops.decode(token, null, null);
        assertTrue(result.isNotYetValid());
        assertFalse(result.isExpired());
    }

    /** alg=none 的令牌签名可被任意伪造，必须显式标出来。 */
    @Test
    void decode_shouldFlagUnsignedToken() {
        String token = buildToken("{\"alg\":\"none\"}", "{\"sub\":\"x\"}", "");
        assertTrue(ops.decode(token, null, null).isUnsigned());
    }

    @Test
    void decode_shouldRejectMalformedToken() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.decode("only.two", null, null));
        assertTrue(ex.getMessage().contains("三段"));
    }

    @Test
    void decode_shouldRejectNonJsonSegment() {
        String token = buildToken("not-json-at-all", "{\"sub\":\"x\"}", "sig");
        assertThrows(IllegalArgumentException.class, () -> ops.decode(token, null, null));
    }

    @Test
    void decode_shouldRejectBlankToken() {
        assertThrows(IllegalArgumentException.class, () -> ops.decode("  ", null, null));
    }

    @Test
    void decode_shouldRejectIllegalSecretEncoding() {
        assertThrows(IllegalArgumentException.class, () -> ops.decode(SAMPLE_TOKEN, "k", "rot13"));
    }

    /** 按 JWT 规范拼一个 base64url（无 padding）令牌。 */
    private String buildToken(String headerJson, String payloadJson, String signature) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + "."
            + signature;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
