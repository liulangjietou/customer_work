package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.core.constant.DevDefaultCredentials;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.UserAuthProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Optional;

/**
 * 用户登录态 JWT 签发与校验（HS256，自签自验，无状态）。
 *
 * <p>starter 提供账户与密码校验能力（{@code UserAccountService}）之外，把登录态令牌的签发/校验作为可复用的
 * 接入层基础设施一并下沉到本包（与 {@link AgentAccessCredential} 同域）；接入方（app）只做装配与业务编排。
 * 密钥取 {@code customer-work.user-auth.jwt-secret}；有效期取 {@code jwt-expire-hours}。为避免过短密钥触发
 * jjwt 的 WeakKeyException，统一把配置密钥做 SHA-256 摘要派生出 256bit 定长签名密钥（既满足 HS256 强度要求，
 * 又不对运维配置的密钥长度做硬约束）。</p>
 *
 * <p><b>安全提示</b>：默认密钥仅供本地开发，生产必须用环境变量 {@code CW_USER_JWT_SECRET} 覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class UserJwtService {

    private static final Logger log = LoggerFactory.getLogger(UserJwtService.class);

    /** 开发用默认密钥（生产必须用 env CW_USER_JWT_SECRET 覆盖，见 application.yml）。 */

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_TENANT = "tenant";
    private static final long HOUR_MS = 3600_000L;

    private final SecretKey signingKey;
    private final long expireMs;

    public UserJwtService(CustomerWorkProperties properties) {
        UserAuthProperties userAuth = properties.getUserAuth();
        String secret = userAuth.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            secret = DevDefaultCredentials.USER_JWT_SECRET;
            log.info("user jwt secret not configured, using dev default (override via CW_USER_JWT_SECRET in prod)");
        }
        this.signingKey = Keys.hmacShaKeyFor(sha256(secret));
        int hours = userAuth.getJwtExpireHours() > 0 ? userAuth.getJwtExpireHours() : 168;
        this.expireMs = hours * HOUR_MS;
    }

    /**
     * 签发默认租户的登录态令牌。
     *
     * <p>保留三参重载是为了不破坏已接入的下游（本类是 starter 的公开 API）。
     * 多租户部署必须用四参版本显式传租户，否则所有用户都会落到默认租户。</p>
     */
    public String issue(String userId, String username, String nickname) {
        return issue(userId, username, nickname, TenantContext.DEFAULT);
    }

    /**
     * 签发登录态令牌：subject=userId，附带 username / nickname / tenant，过期时间为签发时刻 + 有效期。
     *
     * @return 已签名的紧凑 JWT 字符串
     */
    public String issue(String userId, String username, String nickname, String tenantId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .subject(userId)
            .claim(CLAIM_USERNAME, username)
            .claim(CLAIM_NICKNAME, nickname)
            .claim(CLAIM_TENANT, normalizeTenant(tenantId))
            .issuedAt(new Date(now))
            .expiration(new Date(now + expireMs))
            .signWith(signingKey)
            .compact();
    }

    /** 令牌到期的绝对时间戳（毫秒）：供登录响应回传给前端做续期提示。 */
    public long expiresAtMs() {
        return System.currentTimeMillis() + expireMs;
    }

    /**
     * 校验令牌：验签 + 有效期（jjwt 内部校验 exp），全通过返回主体，否则 empty。
     *
     * <p>过期 / 篡改 / 格式非法统一收敛为 {@link Optional#empty()}——不向调用方区分失败原因。</p>
     */
    public Optional<UserPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            String tenantId = normalizeTenant(claims.get(CLAIM_TENANT, String.class));
            if (!TenantContext.isValidTenantId(tenantId)) {
                return Optional.empty();
            }
            return Optional.of(new UserPrincipal(
                claims.getSubject(),
                claims.get(CLAIM_USERNAME, String.class),
                claims.get(CLAIM_NICKNAME, String.class),
                // 多租户上线前签发的令牌没有这个 claim，回落默认租户，避免存量用户被强制登出
                tenantId));
        } catch (Exception e) {
            // 验签失败 / 过期 / 格式非法：统一按未认证处理，不打堆栈（正常的攻击面噪声）
            return Optional.empty();
        }
    }

    private static String normalizeTenant(String tenantId) {
        String resolved = tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT : tenantId;
        return TenantContext.canonicalizeTenantId(resolved);
    }

    /** 把任意长度密钥摘要成 256bit 定长密钥，满足 HS256 强度且不约束配置密钥长度。 */
    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，缺失属不可恢复的环境错误，fast-fail
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
