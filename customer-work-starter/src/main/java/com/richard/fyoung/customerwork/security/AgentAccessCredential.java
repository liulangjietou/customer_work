package com.richard.fyoung.customerwork.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * 坐席访问凭证（无状态 HMAC 令牌，纯工具类，无 Spring 依赖）。
 *
 * <p>令牌格式：{@code agentId:expiresAtMs:Base64Url(HmacSHA256(agentId + ":" + expiresAtMs, secret))}。
 * 服务端只需持有 {@code secret} 即可在无会话存储的前提下校验坐席身份与有效期，避免"客户端自报 agentId
 * 即被信任"的越权风险（对应 {@code ApprovalAuth} 把身份从客户端自报改为服务端凭 token 解析的同一思路）。</p>
 *
 * <p>签名比较用 {@link MessageDigest#isEqual}（常时比较，抗时序侧信道）；{@code agentId} 含分隔符冒号会
 * 破坏令牌可解析性，故签发时 fast-fail 拒绝。</p>
 * @author owlzhangfq@gmail.com
 */
public final class AgentAccessCredential {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = ":";
    private static final int TOKEN_PARTS = 3;

    private AgentAccessCredential() {
    }

    /**
     * 签发令牌。
     *
     * @param agentId     坐席标识（不得包含冒号，否则令牌不可解析）
     * @param expiresAtMs 过期时间戳（毫秒）
     * @param secret      服务端密钥
     * @return 三段式令牌字符串
     * @throws IllegalArgumentException agentId 含冒号
     */
    public static String sign(String agentId, long expiresAtMs, String secret) {
        if (agentId == null || agentId.contains(SEPARATOR)) {
            throw new IllegalArgumentException("agentId must not be null or contain ':'");
        }
        String payload = agentId + SEPARATOR + expiresAtMs;
        String signature = base64Url(hmac(payload, secret));
        return payload + SEPARATOR + signature;
    }

    /**
     * 校验令牌：格式 → 签名（常时比较）→ 有效期，全通过返回其中的 agentId，否则 empty。
     *
     * @param token  待校验令牌
     * @param secret 服务端密钥
     * @param nowMs  当前时间戳（毫秒）
     * @return 校验通过返回 agentId，否则 {@link Optional#empty()}
     */
    public static Optional<String> verify(String token, String secret, long nowMs) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split(SEPARATOR);
        if (parts.length != TOKEN_PARTS) {
            return Optional.empty();
        }
        String agentId = parts[0];
        long expiresAtMs;
        try {
            expiresAtMs = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        byte[] providedSignature;
        try {
            providedSignature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        byte[] expectedSignature = hmac(agentId + SEPARATOR + expiresAtMs, secret);
        // 常时比较，避免按字节短路泄露签名信息
        if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
            return Optional.empty();
        }
        if (nowMs >= expiresAtMs) {
            return Optional.empty();
        }
        return Optional.of(agentId);
    }

    private static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 密钥/算法非法属不可恢复的编程错误，fast-fail 抛出（校验侧不应吞成"验签失败"而掩盖配置问题）
            throw new IllegalStateException("failed to compute HMAC", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
