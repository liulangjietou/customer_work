package com.richard.fyoung.customerwork.infra.config;

import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/** 运行时配置签名契约：固定 HMAC-SHA256，覆盖 revision、contentHash、publishedAt 与 schemaVersion。 */
public final class RuntimeConfigSignature {

    public static final String ALGORITHM = "HMAC-SHA256";

    private RuntimeConfigSignature() {
    }

    public static String sign(CustomerWorkRuntimeConfig config, String secret) {
        Objects.requireNonNull(config, "config");
        if (!StringUtils.hasText(secret)) {
            throw new IllegalArgumentException("runtime config signing secret is missing");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(message(config).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("runtime config signing failed", e);
        }
    }

    /** 未要求签名时只兼容完全无签名的历史配置；出现任一签名字段就必须完整验签。 */
    public static boolean verify(CustomerWorkRuntimeConfig config, Map<String, String> keys,
                                 boolean signatureRequired) {
        if (config == null) {
            return false;
        }
        boolean anySignatureField = StringUtils.hasText(config.getSignatureKeyId())
            || StringUtils.hasText(config.getSignatureAlgorithm())
            || StringUtils.hasText(config.getSignature());
        if (!anySignatureField) {
            return !signatureRequired;
        }
        if (!ALGORITHM.equals(config.getSignatureAlgorithm())
            || !StringUtils.hasText(config.getSignatureKeyId())
            || !StringUtils.hasText(config.getSignature())) {
            return false;
        }
        String secret = keys == null ? null : keys.get(config.getSignatureKeyId());
        if (!StringUtils.hasText(secret)) {
            return false;
        }
        String expected = sign(config, secret);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            config.getSignature().getBytes(StandardCharsets.US_ASCII));
    }

    private static String message(CustomerWorkRuntimeConfig config) {
        return normalize(config.getRevision()) + "\n"
            + normalize(config.getContentHash()) + "\n"
            + normalize(config.getPublishedAt()) + "\n"
            + config.getSchemaVersion();
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
