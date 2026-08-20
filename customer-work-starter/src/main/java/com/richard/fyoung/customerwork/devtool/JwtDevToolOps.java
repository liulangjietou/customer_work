package com.richard.fyoung.customerwork.devtool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * JWT 解析纯函数集：拆解 header/payload、解读标准声明与有效期、可选 HS* 签名校验。
 *
 * <p>面向"排查登录态与第三方对接"这一实际场景：多数情况下要看的是 payload 里到底签了什么、
 * 到底过没过期。签名校验只实现 HMAC（HS256/HS384/HS512，本项目自身的用户令牌即 HS256）；
 * RS、ES、PS 系列非对称算法只解码不验签，结果里以 UNSUPPORTED_ALG 明确标注，不会假装校验通过。</p>
 *
 * <p><b>安全</b>：{@code alg=none} 的令牌会被显式标注——它意味着签名可被任意伪造，见到必须当作
 * 不可信。校验密钥比对走 {@link MessageDigest#isEqual} 常量时间比较。</p>
 *
 * <p>无 Spring 依赖、无状态，参数非法入口 fast-fail。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class JwtDevToolOps {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 展示时间用的时区与格式（与工具箱其它工具一致）。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JWT 的三段结构。 */
    private static final int JWT_PART_COUNT = 3;

    /** 签名校验结论。 */
    private static final String VERIFY_VALID = "VALID";
    private static final String VERIFY_INVALID = "INVALID";
    private static final String VERIFY_NOT_CHECKED = "NOT_CHECKED";
    private static final String VERIFY_UNSUPPORTED_ALG = "UNSUPPORTED_ALG";

    /** 无签名算法标识。 */
    private static final String ALG_NONE = "none";

    /** 密钥文本编码。 */

    /** 时间戳换算。 */
    private static final long MILLIS_PER_SECOND = 1000L;

    /**
     * 解析 JWT。
     *
     * @param token          JWT 字符串（header.payload.signature）
     * @param secret         可选，HS* 签名校验用的密钥；为空则不校验
     * @param secretEncoding 密钥的文本编码：utf8(默认)/hex/base64
     * @return 解析结果
     */
    public JwtDecodeResult decode(String token, String secret, String secretEncoding) {
        DevToolArgs.requireNonBlank(token, "token");
        String trimmed = token.trim();
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != JWT_PART_COUNT) {
            throw new IllegalArgumentException("JWT 必须是以 . 分隔的三段（header.payload.signature），当前 "
                + parts.length + " 段");
        }

        JsonNode header = parseSegment(parts[0], "header");
        JsonNode payload = parseSegment(parts[1], "payload");
        String algorithm = header.path("alg").asText(null);
        ZoneId zoneId = ZoneId.of(DevToolConstants.DEFAULT_ZONE);

        Long expSeconds = readNumericDate(payload, "exp");
        Long nbfSeconds = readNumericDate(payload, "nbf");
        long nowSeconds = Instant.now().getEpochSecond();

        return JwtDecodeResult.builder()
            .algorithm(algorithm)
            .type(header.path("typ").asText(null))
            .header(toPrettyJson(header))
            .payload(toPrettyJson(payload))
            .issuer(payload.path("iss").asText(null))
            .subject(payload.path("sub").asText(null))
            .audience(payload.path("aud").isMissingNode() ? null : payload.path("aud").toString())
            .jwtId(payload.path("jti").asText(null))
            .issuedAt(formatEpochSeconds(readNumericDate(payload, "iat"), zoneId))
            .notBefore(formatEpochSeconds(nbfSeconds, zoneId))
            .expiresAt(formatEpochSeconds(expSeconds, zoneId))
            .expired(expSeconds != null && expSeconds <= nowSeconds)
            .notYetValid(nbfSeconds != null && nbfSeconds > nowSeconds)
            .secondsRemaining(expSeconds == null ? null : expSeconds - nowSeconds)
            .unsigned(ALG_NONE.equalsIgnoreCase(algorithm))
            .signatureStatus(verifySignature(parts, algorithm, secret, secretEncoding))
            .build();
    }

    /** 校验签名：无密钥不校验；非 HS* 明确标为不支持，绝不含糊成"通过"。 */
    private String verifySignature(String[] parts, String algorithm, String secret, String secretEncoding) {
        if (secret == null || secret.trim().isEmpty()) {
            return VERIFY_NOT_CHECKED;
        }
        String macAlgorithm = toMacAlgorithm(algorithm);
        if (macAlgorithm == null) {
            return VERIFY_UNSUPPORTED_ALG;
        }
        byte[] keyBytes = decodeSecret(secret, normalizeEncoding(secretEncoding));
        String signingInput = parts[0] + "." + parts[1];
        try {
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(keyBytes, macAlgorithm));
            byte[] expected = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] actual = decodeBase64Url(parts[2], "signature");
            return MessageDigest.isEqual(expected, actual) ? VERIFY_VALID : VERIFY_INVALID;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("签名校验失败：" + e.getMessage(), e);
        }
    }

    /** JWT alg 映射到 JCE 的 Mac 算法名；非 HS* 返回 null 表示不支持验签。 */
    private String toMacAlgorithm(String algorithm) {
        if (algorithm == null) {
            return null;
        }
        switch (algorithm.trim().toUpperCase(Locale.ROOT)) {
            case "HS256":
                return "HmacSHA256";
            case "HS384":
                return "HmacSHA384";
            case "HS512":
                return "HmacSHA512";
            default:
                return null;
        }
    }

    /** 解析某一段：base64url 解码后按 JSON 解析。 */
    private JsonNode parseSegment(String segment, String name) {
        byte[] decoded = decodeBase64Url(segment, name);
        try {
            return MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(name + " 段解码后不是合法 JSON：" + e.getOriginalMessage(), e);
        }
    }

    /** base64url 解码（JWT 各段按 RFC 7515 省略了 padding）。 */
    private byte[] decodeBase64Url(String segment, String name) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " 段不是合法的 base64url 编码：" + e.getMessage(), e);
        }
    }

    /** 读取 NumericDate 型声明（秒级时间戳）；缺失或非数字返回 null。 */
    private Long readNumericDate(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isNumber() ? node.asLong() : null;
    }

    /** 秒级时间戳转可读时间；null 原样返回 null。 */
    private String formatEpochSeconds(Long epochSeconds, ZoneId zoneId) {
        if (epochSeconds == null) {
            return null;
        }
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochSeconds * MILLIS_PER_SECOND), zoneId)
            .format(TIME_FORMATTER);
    }

    /** 树模型转带缩进的 JSON 文本。 */
    private String toPrettyJson(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // 已解析成功的树再序列化不会失败，仅作兜底
            throw new IllegalArgumentException("序列化失败：" + e.getOriginalMessage(), e);
        }
    }

    /** 规范化密钥编码。 */
    private String normalizeEncoding(String encoding) {
        if (encoding == null || encoding.trim().isEmpty()) {
            return DevToolConstants.ENCODING_UTF8;
        }
        String upper = encoding.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (!DevToolConstants.ENCODING_UTF8.equals(upper) && !DevToolConstants.ENCODING_HEX.equals(upper) && !DevToolConstants.ENCODING_BASE64.equals(upper)) {
            throw new IllegalArgumentException("secretEncoding 仅支持 utf8/hex/base64，当前 " + encoding);
        }
        return upper;
    }

    /** 按编码解出密钥字节。 */
    private byte[] decodeSecret(String secret, String encoding) {
        switch (encoding) {
            case DevToolConstants.ENCODING_HEX:
                String cleaned = secret.replaceAll("\\s", "");
                if (!cleaned.matches("[0-9a-fA-F]+") || cleaned.length() % 2 != 0) {
                    throw new IllegalArgumentException("secret 不是合法的 hex（只允许 0-9、a-f，且长度为偶数）");
                }
                byte[] bytes = new byte[cleaned.length() / 2];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
                }
                return bytes;
            case DevToolConstants.ENCODING_BASE64:
                try {
                    return Base64.getDecoder().decode(secret.trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("secret 不是合法的 Base64：" + e.getMessage(), e);
                }
            case DevToolConstants.ENCODING_UTF8:
            default:
                return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * JWT 解析结果。时间类字段均已按 Asia/Shanghai 格式化为 yyyy-MM-dd HH:mm:ss，缺失的声明为 null。
     */
    @Getter
    @Builder
    public static class JwtDecodeResult {
        /** 签名算法（header.alg）。 */
        private final String algorithm;
        /** 令牌类型（header.typ）。 */
        private final String type;
        /** header 的格式化 JSON。 */
        private final String header;
        /** payload 的格式化 JSON。 */
        private final String payload;
        /** 签发方 iss。 */
        private final String issuer;
        /** 主体 sub。 */
        private final String subject;
        /** 受众 aud（原样 JSON，可能是字符串或数组）。 */
        private final String audience;
        /** 令牌 ID jti。 */
        private final String jwtId;
        /** 签发时间 iat。 */
        private final String issuedAt;
        /** 生效时间 nbf。 */
        private final String notBefore;
        /** 过期时间 exp。 */
        private final String expiresAt;
        /** 是否已过期（无 exp 声明时为 false）。 */
        private final boolean expired;
        /** 是否尚未生效（无 nbf 声明时为 false）。 */
        private final boolean notYetValid;
        /** 距过期的秒数，负数表示已过期；无 exp 声明时为 null。 */
        private final Long secondsRemaining;
        /** 是否是 alg=none 的无签名令牌（签名可被任意伪造，必须当作不可信）。 */
        private final boolean unsigned;
        /** 签名校验结论：VALID / INVALID / NOT_CHECKED(未提供密钥) / UNSUPPORTED_ALG(非 HS* 不验签)。 */
        private final String signatureStatus;
    }
}
