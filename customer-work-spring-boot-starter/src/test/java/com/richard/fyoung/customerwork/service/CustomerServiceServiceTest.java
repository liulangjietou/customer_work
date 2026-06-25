package com.richard.fyoung.customerwork.service;

import com.richard.fyoung.customerwork.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.dto.IntentResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话服务单测（AgentScope 2.0 迁移版）：用 Mockito 隔离模型与框架，验证编排逻辑——
 * 多轮共享 Agent、错误兜底、流式拼接、按 RuntimeContext 调用、结束会话清理状态。
 *
 * <p>2.0 中会话状态由框架按 {@code (userId, sessionId)} 自动持久化，本测试不再校验显式 save 调用。</p>
 * @author owlzhangfq@gmail.com
 */
class CustomerServiceServiceTest {

    private CustomerServiceAgentFactory factory;
    private ReActAgent agent;
    private SessionStateManager sessionStateManager;
    private CustomerServiceService service;

    @BeforeEach
    void setUp() {
        factory = mock(CustomerServiceAgentFactory.class);
        agent = mock(ReActAgent.class);
        sessionStateManager = mock(SessionStateManager.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        when(factory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
        service = new CustomerServiceService(factory, sessionStateManager);
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
        when(agent.call(anyString(), any(RuntimeContext.class)))
            .thenReturn(Mono.just(assistantMsg("您好，有什么可以帮您？")));

        StepVerifier.create(service.chat("u1", "你好"))
            .expectNext("您好，有什么可以帮您？")
            .verifyComplete();
    }

    @Test
    void chat_shouldReuseAgentAcrossTurns_forSameSession() {
        when(agent.call(anyString(), any(RuntimeContext.class))).thenReturn(Mono.just(assistantMsg("ok")));

        service.chat("same-session", "第一轮").block();
        service.chat("same-session", "第二轮").block();

        // 同一会话只装配一次 Agent（多轮共享上下文）
        verify(factory, org.mockito.Mockito.times(1)).createAgent("same-session");
    }

    @Test
    void chat_shouldFallback_whenAgentFails() {
        when(agent.call(anyString(), any(RuntimeContext.class)))
            .thenReturn(Mono.error(new RuntimeException("model down")));

        StepVerifier.create(service.chat("u2", "查询订单"))
            .assertNext(reply -> org.junit.jupiter.api.Assertions.assertTrue(reply.contains("系统繁忙")))
            .verifyComplete();
    }

    @Test
    void chatStream_shouldEmitIncrementalChunks() {
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
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
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
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
        when(agent.call(anyString(), eq(IntentResult.class), any(RuntimeContext.class)))
            .thenReturn(Mono.just(structuredMsg));

        StepVerifier.create(service.classifyIntent("u5", "这个订单我要退款"))
            .expectNext(expected)
            .verifyComplete();
    }

    @Test
    void classifyIntent_shouldFallback_whenParsingFails() {
        when(agent.call(anyString(), eq(IntentResult.class), any(RuntimeContext.class)))
            .thenReturn(Mono.error(new RuntimeException("bad json")));

        StepVerifier.create(service.classifyIntent("u6", "随便说点啥"))
            .assertNext(result -> org.junit.jupiter.api.Assertions.assertEquals("other", result.intent()))
            .verifyComplete();
    }

    @Test
    void interrupt_shouldCallAgentInterrupt_whenSessionActive() {
        when(agent.call(anyString(), any(RuntimeContext.class))).thenReturn(Mono.just(assistantMsg("ok")));
        service.chat("active", "hi").block();   // 触发 Agent 装配进缓存

        boolean result = service.interrupt("active");

        org.junit.jupiter.api.Assertions.assertTrue(result);
        verify(agent).interrupt(any(RuntimeContext.class));
    }

    @Test
    void interrupt_shouldReturnFalse_whenNoActiveSession() {
        org.junit.jupiter.api.Assertions.assertFalse(service.interrupt("never-started"));
    }

    @Test
    void endSession_shouldRemoveCachedAgent_andDeleteState() {
        when(agent.call(anyString(), any(RuntimeContext.class))).thenReturn(Mono.just(assistantMsg("ok")));
        service.chat("to-end", "hi").block();

        service.endSession("to-end");
        // 删除持久化状态
        verify(sessionStateManager).delete("tenant", "to-end");
        // 结束后再对话会重新装配 Agent
        service.chat("to-end", "again").block();
        verify(factory, org.mockito.Mockito.times(2)).createAgent("to-end");
    }

    /**
     * 意图识别用独立的 "intent:" 前缀 Agent，不复用会话 Agent，避免分类指令污染真实对话记忆。
     */
    @Test
    void classifyIntent_shouldUseSeparateAgent() {
        IntentResult expected = new IntentResult("refund", "", true, "x");
        Msg structuredMsg = mock(Msg.class);
        when(structuredMsg.getStructuredData(IntentResult.class)).thenReturn(expected);
        when(agent.call(anyString(), eq(IntentResult.class), any(RuntimeContext.class)))
            .thenReturn(Mono.just(structuredMsg));

        service.classifyIntent("u7", "我要退款").block();

        // 用独立 intent: 前缀 Agent，不碰会话 Agent
        verify(factory).createAgent("intent:u7");
        verify(factory, org.mockito.Mockito.never()).createAgent("u7");
    }
}
