package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * 坐席访问凭证（无状态 HMAC 令牌，纯工具类，无 Spring 依赖）。
 *
 * <p>单租户旧令牌格式：{@code agentId:expiresAtMs:signature}；多租户格式：
 * {@code agentId:tenantId:expiresAtMs:signature}。签名覆盖签名前的全部字段，租户身份不能由请求方篡改。
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
    private static final int LEGACY_TOKEN_PARTS = 3;
    private static final int TENANT_TOKEN_PARTS = 4;

    private AgentAccessCredential() {
    }

    /**
     * 签发令牌。
     *
     * @param agentId     坐席标识（不得包含冒号，否则令牌不可解析）
     * @param expiresAtMs 过期时间戳（毫秒）
     * @param secret      服务端密钥
     * @return 单租户三段式令牌字符串
     * @throws IllegalArgumentException agentId 含冒号
     */
    public static String sign(String agentId, long expiresAtMs, String secret) {
        requireTokenPart(agentId, "agentId");
        String payload = agentId + SEPARATOR + expiresAtMs;
        String signature = base64Url(hmac(payload, secret));
        return payload + SEPARATOR + signature;
    }

    /** 签发绑定租户的坐席令牌；多租户部署必须使用本入口。 */
    public static String sign(String agentId, String tenantId, long expiresAtMs, String secret) {
        requireTokenPart(agentId, "agentId");
        requireTokenPart(tenantId, "tenantId");
        String canonicalTenant = TenantContext.canonicalizeTenantId(tenantId);
        String payload = agentId + SEPARATOR + canonicalTenant + SEPARATOR + expiresAtMs;
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
        return verifyIdentity(token, secret, nowMs).map(AgentIdentity::agentId);
    }

    /** 校验并返回签名内的坐席与租户身份；旧三段令牌的 tenantId 为 {@code null}。 */
    public static Optional<AgentIdentity> verifyIdentity(String token, String secret, long nowMs) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split(SEPARATOR, -1);
        if (parts.length != LEGACY_TOKEN_PARTS && parts.length != TENANT_TOKEN_PARTS) {
            return Optional.empty();
        }
        String agentId = parts[0];
        String tenantId = parts.length == TENANT_TOKEN_PARTS ? parts[1] : null;
        int expiresIndex = parts.length - 2;
        int signatureIndex = parts.length - 1;
        long expiresAtMs;
        try {
            expiresAtMs = Long.parseLong(parts[expiresIndex]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        byte[] providedSignature;
        try {
            providedSignature = Base64.getUrlDecoder().decode(parts[signatureIndex]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String payload = parts.length == TENANT_TOKEN_PARTS
            ? agentId + SEPARATOR + tenantId + SEPARATOR + expiresAtMs
            : agentId + SEPARATOR + expiresAtMs;
        byte[] expectedSignature = hmac(payload, secret);
        // 常时比较，避免按字节短路泄露签名信息
        if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
            return Optional.empty();
        }
        if (nowMs >= expiresAtMs) {
            return Optional.empty();
        }
        if (agentId.isBlank() || (tenantId != null && tenantId.isBlank())) {
            return Optional.empty();
        }
        if (tenantId != null && !TenantContext.isValidTenantId(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(new AgentIdentity(agentId, TenantContext.canonicalizeTenantId(tenantId)));
    }

    /** 经过 HMAC 验证的坐席身份。 */
    public record AgentIdentity(String agentId, String tenantId) {
    }

    private static void requireTokenPart(String value, String name) {
        if (value == null || value.isBlank() || value.contains(SEPARATOR)) {
            throw new IllegalArgumentException(name + " must not be blank or contain ':'");
        }
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
