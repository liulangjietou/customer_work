package com.richard.fyoung.customeradmin.ticket.service;

import com.richard.fyoung.customeradmin.ticket.client.CustomerWorkTicketClient;
import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientProperties;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.WsCredentialVO;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link UserTicketService} 单测：mock client 验证坐席操作参数透传，并验证 WS 接入凭证
 * 由本地正确签发（token 可被 {@link AgentAccessCredential#verify} 校验、有效期正确）。
 * @author owlzhangfq@gmail.com
 */
class UserTicketServiceTest {

    private static final String SECRET = "unit-test-agent-secret-0001";
    private static final long EXPIRE_HOURS = 12;

    private CustomerWorkTicketClient client;
    private CustomerWorkClientProperties properties;
    private UserTicketService service;

    @BeforeEach
    void setUp() {
        client = mock(CustomerWorkTicketClient.class);
        properties = new CustomerWorkClientProperties();
        properties.setAgentSecret(SECRET);
        properties.setCredentialExpireHours(EXPIRE_HOURS);
        properties.setWsUrl("ws://localhost:8080/ws/agent");
        service = new UserTicketService(client, properties);
    }

    @Test
    void ticketOperations_shouldPassThroughToClient() {
        // 工单号是 8080 侧 starter 生成的字符串 "TK-<uuid>"，不是数值主键
        String ticketId = "TK-a1b2c3d4";

        TicketPageQuery query = new TicketPageQuery();
        service.page(query);
        verify(client).page(query);

        service.detail(ticketId);
        verify(client).detail(ticketId);

        service.messages(ticketId, 100L, 20);
        verify(client).messages(ticketId, 100L, 20);

        service.claim(ticketId);
        verify(client).claim(ticketId);

        service.reply(ticketId, "hello");
        verify(client).reply(ticketId, "hello");

        service.hold(ticketId, "wait customer");
        verify(client).hold(ticketId, "wait customer");

        service.resume(ticketId);
        verify(client).resume(ticketId);

        service.transfer(ticketId, "bob");
        verify(client).transfer(ticketId, "bob");

        service.resolve(ticketId, "done");
        verify(client).resolve(ticketId, "done");

        service.close(ticketId, "user confirmed");
        verify(client).close(ticketId, "user confirmed");

        service.updatePriority(ticketId, "HIGH");
        verify(client).updatePriority(ticketId, "HIGH");

        service.updateCategory(ticketId, "REFUND");
        verify(client).updateCategory(ticketId, "REFUND");
    }

    @Test
    void issueWsCredential_shouldSignVerifiableTokenForAgent() {
        long before = System.currentTimeMillis();
        WsCredentialVO credential = service.issueWsCredential("alice");
        long after = System.currentTimeMillis();

        assertEquals("alice", credential.agentId());
        assertEquals("ws://localhost:8080/ws/agent", credential.wsUrl());

        // token 可被同密钥校验，且解析出的 agentId 正确
        Optional<String> verified = AgentAccessCredential.verify(
            credential.token(), SECRET, System.currentTimeMillis());
        assertTrue(verified.isPresent());
        assertEquals("alice", verified.get());

        // 有效期 = 签发时刻 + 12 小时（允许方法执行耗时的窗口）
        long expectedMin = before + EXPIRE_HOURS * 3600_000L;
        long expectedMax = after + EXPIRE_HOURS * 3600_000L;
        assertTrue(credential.expiresAtMs() >= expectedMin && credential.expiresAtMs() <= expectedMax);
    }
}
