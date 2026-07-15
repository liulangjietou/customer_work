package com.richard.fyoung.customerwork.security;

import org.junit.jupiter.api.Test;

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
    void verify_expiredToken_shouldReject() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", now - 1_000, SECRET);
        assertTrue(AgentAccessCredential.verify(token, SECRET, now).isEmpty());
    }

    @Test
    void verify_tamperedSignature_shouldReject() {
        long now = System.currentTimeMillis();
        String token = AgentAccessCredential.sign("agent-1", now + 60_000, SECRET);

        // 翻转签名最后一个字符
        char last = token.charAt(token.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

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
}
