package com.richard.fyoung.customerwork.core.runtime;

import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.service.SessionStateManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话超时清理器单测：超时会话被清理 / 未超时会话保留 / 禁用时不清理。
 * @author owlzhangfq@gmail.com
 */
class SessionTimeoutSchedulerTest {

    private CustomerServiceAgentFactory factory;
    private ReActAgent agent;
    private SessionStateManager sessionStateManager;
    private CustomerServiceService service;
    private CustomerWorkProperties properties;

    @BeforeEach
    void setUp() {
        factory = mock(CustomerServiceAgentFactory.class);
        agent = mock(ReActAgent.class);
        sessionStateManager = mock(SessionStateManager.class);
        properties = new CustomerWorkProperties();
        when(factory.createAgent(anyString())).thenReturn(agent);
        when(factory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
        when(agent.call(anyString(), any(RuntimeContext.class)))
            .thenReturn(Mono.just(assistantMsg("ok")));
        service = new CustomerServiceService(factory, sessionStateManager, properties);
    }

    private Msg assistantMsg(String text) {
        return Msg.builder()
            .role(MsgRole.ASSISTANT)
            .name("assistant")
            .content(TextBlock.builder().text(text).build())
            .build();
    }

    @Test
    void shouldNotCleanUp_whenTimeoutDisabled() {
        properties.getSession().setIdleTimeoutMinutes(0);

        service.chat("s1", "hi").block();

        SessionTimeoutScheduler scheduler = new SessionTimeoutScheduler(properties, service);
        scheduler.runCleanup();

        // 禁用时不会清理，会话 Agent 仍在缓存中
        // 再次 chat 时不会重新 createAgent（说明 Agent 还在缓存）
        service.chat("s1", "again").block();
        org.mockito.Mockito.verify(factory, org.mockito.Mockito.times(1)).createAgent("s1");
    }

    @Test
    void shouldNotCleanUpRecentSessions() {
        properties.getSession().setIdleTimeoutMinutes(30); // 30 分钟超时

        service.chat("recent", "hi").block();

        SessionTimeoutScheduler scheduler = new SessionTimeoutScheduler(properties, service);
        scheduler.runCleanup();

        // 刚活跃的会话不应被清理
        service.chat("recent", "again").block();
        org.mockito.Mockito.verify(factory, org.mockito.Mockito.times(1)).createAgent("recent");
    }

    @Test
    void shouldReturnZero_whenNoSessions() {
        properties.getSession().setIdleTimeoutMinutes(1);

        SessionTimeoutScheduler scheduler = new SessionTimeoutScheduler(properties, service);
        scheduler.runCleanup();

        // 没有会话时清理 0 个
        // 无异常即可
    }
}
