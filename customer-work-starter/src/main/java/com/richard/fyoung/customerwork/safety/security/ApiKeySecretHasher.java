package com.richard.fyoung.customerwork.safety.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** API Key secret 摘要工具；凭据配置与运行时校验使用同一算法。 */
public final class ApiKeySecretHasher {

    private static final int SHA256_HEX_LENGTH = 64;

    private ApiKeySecretHasher() {
    }

    public static String sha256Hex(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("API key secret cannot be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != SHA256_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }
}
