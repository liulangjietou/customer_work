package com.example.customerwork.service;

import com.example.customerwork.agent.CustomerServiceAgentFactory;
import com.example.customerwork.dto.IntentResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话服务单测：用 Mockito 隔离模型与框架，验证编排逻辑——
 * 多轮共享 Agent、错误兜底、流式拼接、会话持久化的调用。
 */
class CustomerServiceServiceTest {

    private CustomerServiceAgentFactory factory;
    private ReActAgent agent;
    private Session session;
    private CustomerServiceService service;

    @BeforeEach
    void setUp() {
        factory = mock(CustomerServiceAgentFactory.class);
        agent = mock(ReActAgent.class);
        // 用真实的 InMemorySession，验证 saveTo/loadIfExists 真正可用
        session = new InMemorySession();
        when(factory.createAgent(anyString())).thenReturn(agent);
        service = new CustomerServiceService(factory, session);
    }

    private Msg assistantMsg(String text) {
        return Msg.builder()
            .role(MsgRole.ASSISTANT)
            .name("assistant")
            .content(TextBlock.builder().text(text).build())
            .build();
    }

    @Test
    void chat_shouldReturnAssistantReply() {
        when(agent.call(any(Msg.class))).thenReturn(Mono.just(assistantMsg("您好，有什么可以帮您？")));

        StepVerifier.create(service.chat("u1", "你好"))
            .expectNext("您好，有什么可以帮您？")
            .verifyComplete();

        // 持久化在 boundedElastic 上异步触发，最终应被调用
        verify(agent, timeout(2000)).saveTo(any(Session.class), any(SessionKey.class));
    }

    @Test
    void chat_shouldReuseAgentAcrossTurns_forSameSession() {
        when(agent.call(any(Msg.class))).thenReturn(Mono.just(assistantMsg("ok")));

        service.chat("same-session", "第一轮").block();
        service.chat("same-session", "第二轮").block();

        // 同一会话只装配一次 Agent（多轮共享上下文）
        verify(factory, org.mockito.Mockito.times(1)).createAgent("same-session");
    }

    @Test
    void chat_shouldFallback_whenAgentFails() {
        when(agent.call(any(Msg.class))).thenReturn(Mono.error(new RuntimeException("model down")));

        StepVerifier.create(service.chat("u2", "查询订单"))
            .assertNext(reply -> org.junit.jupiter.api.Assertions.assertTrue(reply.contains("系统繁忙")))
            .verifyComplete();
    }

    @Test
    void chatStream_shouldEmitIncrementalChunks() {
        when(agent.stream(any(Msg.class), any(StreamOptions.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, assistantMsg("您"), false),
                new Event(EventType.REASONING, assistantMsg("好"), false),
                new Event(EventType.AGENT_RESULT, assistantMsg("！"), true)));

        StepVerifier.create(service.chatStream("u3", "你好"))
            .expectNext("您")
            .expectNext("好")
            .expectNext("！")
            .verifyComplete();
    }

    @Test
    void chatStream_shouldFallback_whenStreamFails() {
        when(agent.stream(any(Msg.class), any(StreamOptions.class)))
            .thenReturn(Flux.error(new RuntimeException("stream down")));

        StepVerifier.create(service.chatStream("u4", "你好"))
            .assertNext(chunk -> org.junit.jupiter.api.Assertions.assertTrue(chunk.contains("系统繁忙")))
            .verifyComplete();
    }

    @Test
    void classifyIntent_shouldReturnStructuredResult() {
        IntentResult expected = new IntentResult("refund", "20260613001", true, "用户要求退款");
        Msg structuredMsg = mock(Msg.class);
        when(structuredMsg.getStructuredData(IntentResult.class)).thenReturn(expected);
        when(agent.call(any(Msg.class), eq(IntentResult.class))).thenReturn(Mono.just(structuredMsg));

        StepVerifier.create(service.classifyIntent("u5", "这个订单我要退款"))
            .expectNext(expected)
            .verifyComplete();
    }

    @Test
    void classifyIntent_shouldFallback_whenParsingFails() {
        when(agent.call(any(Msg.class), eq(IntentResult.class)))
            .thenReturn(Mono.error(new RuntimeException("bad json")));

        StepVerifier.create(service.classifyIntent("u6", "随便说点啥"))
            .assertNext(result -> org.junit.jupiter.api.Assertions.assertEquals("other", result.intent()))
            .verifyComplete();
    }

    @Test
    void interrupt_shouldCallAgentInterrupt_whenSessionActive() {
        when(agent.call(any(Msg.class))).thenReturn(Mono.just(assistantMsg("ok")));
        service.chat("active", "hi").block();   // 触发 Agent 装配进缓存

        boolean result = service.interrupt("active");

        org.junit.jupiter.api.Assertions.assertTrue(result);
        verify(agent).interrupt();
    }

    @Test
    void interrupt_shouldReturnFalse_whenNoActiveSession() {
        org.junit.jupiter.api.Assertions.assertFalse(service.interrupt("never-started"));
    }

    @Test
    void endSession_shouldRemoveCachedAgent() {
        when(agent.call(any(Msg.class))).thenReturn(Mono.just(assistantMsg("ok")));
        service.chat("to-end", "hi").block();

        service.endSession("to-end");
        // 结束后再对话会重新装配 Agent
        service.chat("to-end", "again").block();
        verify(factory, org.mockito.Mockito.times(2)).createAgent("to-end");
    }
}
