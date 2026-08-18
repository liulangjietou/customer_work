package com.richard.fyoung.customerwork.safety.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐席访问凭证单测：签发-校验往返、过期拒绝、篡改（agentId / 签名）拒绝、错误密钥拒绝、agentId 带冒号拒绝签发。
 * @author owlzhangfq@gmail.com
 */
class AgentAccessCredentialTest {

    private static final String SECRET = "top-secret-key";

    @Test
    void signAndVerify_shouldRoundTrip() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", now + 60_000, SECRET);

        Optional<String> agentId = AgentAccessCredential.verify(token, SECRET, now);
        assertTrue(agentId.isPresent());
        assertEquals("agent-1", agentId.get());
    }

    @Test
    void tenantToken_shouldBindAgentAndTenant() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", "tenant-a", now + 60_000, SECRET);

        AgentAccessCredential.AgentIdentity identity = AgentAccessCredential
            .verifyIdentity(token, SECRET, now).orElseThrow();
        assertEquals("agent-1", identity.agentId());
        assertEquals("tenant-a", identity.tenantId());
    }

    @Test
    void tenantToken_tamperedTenant_shouldReject() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", "tenant-a", now + 60_000, SECRET);
        String tampered = token.replaceFirst("tenant-a", "tenant-b");

        assertTrue(AgentAccessCredential.verifyIdentity(tampered, SECRET, now).isEmpty());
    }

    @Test
    void verify_expiredToken_shouldReject() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", now - 1_000, SECRET);
        assertTrue(AgentAccessCredential.verify(token, SECRET, now).isEmpty());
    }

    @Test
    void verify_tamperedSignature_shouldReject() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", now + 60_000, SECRET);

        // 对签名解码后的首字节翻一位再重新编码：base64url 尾字符的低位落在未用填充比特上，直接翻转尾字符
        // 可能解码出完全相同的字节（验签仍通过，测试偶发失败）；改从字节层面翻位，保证解码字节一定不同。
        String[] parts = token.split(":");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 0x01;
        String tampered = parts[0] + ":" + parts[1] + ":"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertTrue(AgentAccessCredential.verify(tampered, SECRET, now).isEmpty());
    }

    @Test
    void verify_tamperedAgentId_shouldReject() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", now + 60_000, SECRET);
        String[] parts = token.split(":");
        String forged = "agent-9:" + parts[1] + ":" + parts[2];

        assertTrue(AgentAccessCredential.verify(forged, SECRET, now).isEmpty());
    }

    @Test
    void verify_wrongSecret_shouldReject() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", now + 60_000, SECRET);
        assertTrue(AgentAccessCredential.verify(token, "other-secret", now).isEmpty());
    }

    @Test
    void verify_malformedToken_shouldReject() {
        long now = System.currentTimeMillis();
        assertTrue(AgentAccessCredential.verify(null, SECRET, now).isEmpty());
        assertTrue(AgentAccessCredential.verify("", SECRET, now).isEmpty());
        assertTrue(AgentAccessCredential.verify("only-two:parts", SECRET, now).isEmpty());
        assertTrue(AgentAccessCredential.verify("a:not-a-number:sig", SECRET, now).isEmpty());
    }

    @Test
    void sign_agentIdWithColon_shouldFastFail() {
        assertThrows(IllegalArgumentException.class,
            () -> AgentAccessCredential.sign("agent:1", System.currentTimeMillis() + 1000, SECRET));
    }

    @Test
    void sign_blankTenant_shouldFastFail() {
        assertThrows(IllegalArgumentException.class,
            () -> AgentAccessCredential.sign(
                "agent-1", " ", System.currentTimeMillis() + 1000, SECRET));
    }
}
