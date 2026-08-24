package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户登录态 JWT 服务单测：签发/验签往返、篡改拒绝、过期拒绝。
 * @author owlzhangfq@gmail.com
 */
class UserJwtServiceTest {

    private static final String SECRET = "unit-test-secret";

    private UserJwtService service() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getUserAuth().setJwtSecret(SECRET);
        props.getUserAuth().setJwtExpireHours(1);
        return new UserJwtService(props);
    }

    @Test
    void issueThenVerify_shouldRoundTrip() {
        UserJwtService svc = service();
        String token = svc.issue("U1", "alice", "Alice", "Tenant-A");
        Optional<UserPrincipal> principal = svc.verify(token);
        assertTrue(principal.isPresent());
        assertEquals("U1", principal.get().userId());
        assertEquals("alice", principal.get().username());
        assertEquals("Alice", principal.get().nickname());
        assertEquals("Tenant-A", principal.get().tenantId(), "存量业务租户大小写不能改变外部资源命名空间");
        assertEquals(0L, principal.get().accessEpoch());
    }

    @Test
    void issueWithAccessEpoch_shouldBindCredentialToTenantVersion() {
        UserPrincipal principal = service().verify(
            service().issue("U1", "alice", "Alice", "tenant-a", 9L)).orElseThrow();

        assertEquals("tenant-a", principal.tenantId());
        assertEquals(9L, principal.accessEpoch());
    }

    @Test
    void issue_shouldFreezeUserSessionEpoch() {
        UserPrincipal principal = service().verify(
            service().issue("U1", "alice", "Alice", "tenant-a", 9L, 3L)).orElseThrow();

        assertEquals(3L, principal.sessionEpoch());
    }

    @Test
    void verify_tamperedToken_shouldReturnEmpty() {
        UserJwtService svc = service();
        String token = svc.issue("U1", "alice", "Alice");
        // 翻转签名段倒数第二个字符：base64url 末位字符含 2 个补齐位（256 bit 签名编码为 43 字符），
        // 翻转末位可能解码出同一签名导致偶发误绿；非末位字符 6 位全有效，翻转必然改变签名
        int pos = token.length() - 2;
        char target = token.charAt(pos);
        String tampered = token.substring(0, pos) + (target == 'a' ? 'b' : 'a') + token.substring(pos + 1);
        assertTrue(svc.verify(tampered).isEmpty());
    }

    @Test
    void verify_expiredToken_shouldReturnEmpty() throws Exception {
        UserJwtService svc = service();
        // 用相同派生密钥手造一个已过期令牌
        SecretKey key = Keys.hmacShaKeyFor(
            MessageDigest.getInstance("SHA-256").digest(SECRET.getBytes(StandardCharsets.UTF_8)));
        long past = System.currentTimeMillis() - 60_000;
        String expired = Jwts.builder()
            .subject("U1")
            .issuedAt(new Date(past - 60_000))
            .expiration(new Date(past))
            .signWith(key)
            .compact();
        assertTrue(svc.verify(expired).isEmpty());
    }

    @Test
    void verify_blankToken_shouldReturnEmpty() {
        assertTrue(service().verify("  ").isEmpty());
        assertTrue(service().verify(null).isEmpty());
    }

    @Test
    void verify_signedTokenWithLegacyTenant_shouldReturnEmpty() {
        UserJwtService svc = service();
        String token = svc.issue("U1", "alice", "Alice", "__platform__");

        assertTrue(svc.verify(token).isEmpty());
    }
}
