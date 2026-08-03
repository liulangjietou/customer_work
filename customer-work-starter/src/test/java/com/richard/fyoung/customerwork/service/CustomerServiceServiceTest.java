package com.richard.fyoung.customerwork.service;

import com.richard.fyoung.customerwork.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.dto.IntentResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

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
    void chatStream_shouldEmitTextDeltas_andDropAggregatedResult() {
        // streamEvents 语义：正文逐块走 TEXT_BLOCK_DELTA，末尾的 AGENT_RESULT 只是同一段文本的汇总。
        // 汇总事件不得二次下发，否则用户看到重复文本；思考增量（THINKING_BLOCK_DELTA）也不下发。
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new TextBlockDeltaEvent("r1", "b1", "您"),
                new ThinkingBlockDeltaEvent("r1", "t1", "让我想想"),
                new TextBlockDeltaEvent("r1", "b1", "好"),
                new AgentResultEvent(assistantMsg("您好"))));

        StepVerifier.create(service.chatStream("u3", "你好"))
            .expectNext("您")
            .expectNext("好")
            .verifyComplete();
    }

    @Test
    void chatStream_nonStreamingFallback_shouldEmitAggregatedResultOnce() {
        // 非流式模型兜底：一个 TEXT_BLOCK_DELTA 都没有时，用 AGENT_RESULT 的全文补一次，避免空回复
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new AgentResultEvent(assistantMsg("您好"))));

        StepVerifier.create(service.chatStream("u3b", "你好"))
            .expectNext("您好")
            .verifyComplete();
    }

    @Test
    void chatStream_shouldFallback_whenStreamFails() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.error(new RuntimeException("stream down")));

        StepVerifier.create(service.chatStream("u4", "你好"))
            .assertNext(chunk -> org.junit.jupiter.api.Assertions.assertTrue(chunk.contains("系统繁忙")))
            .verifyComplete();
    }

    @Test
    void classifyIntent_shouldReturnStructuredResult() {
        IntentResult expected = new IntentResult("refund", "20260613001", true, "用户要求退款");
        Msg structuredMsg = mock(Msg.class);
        when(structuredMsg.hasStructuredData()).thenReturn(true);
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

    /**
     * agentscope-java #1852/#1699 已知限制：fallback 结构化输出路径下，模型本轮没有调用
     * generate_response 工具时，框架不抛异常、不设特殊标记，只是 Msg.hasStructuredData()==false。
     * 这种情况不该走异常兜底分支，应直接识别为"未命中"并优雅降级为 other。
     */
    @Test
    void classifyIntent_shouldFallback_whenModelSkipsStructuredOutputTool() {
        Msg plainTextMsg = mock(Msg.class);
        when(plainTextMsg.hasStructuredData()).thenReturn(false);
        when(agent.call(anyString(), eq(IntentResult.class), any(RuntimeContext.class)))
            .thenReturn(Mono.just(plainTextMsg));

        StepVerifier.create(service.classifyIntent("u8", "随便聊聊"))
            .assertNext(result -> org.junit.jupiter.api.Assertions.assertEquals("other", result.intent()))
            .verifyComplete();
        org.mockito.Mockito.verify(plainTextMsg, org.mockito.Mockito.never())
            .getStructuredData(IntentResult.class);
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
        when(structuredMsg.hasStructuredData()).thenReturn(true);
        when(structuredMsg.getStructuredData(IntentResult.class)).thenReturn(expected);
        when(agent.call(anyString(), eq(IntentResult.class), any(RuntimeContext.class)))
            .thenReturn(Mono.just(structuredMsg));

        service.classifyIntent("u7", "我要退款").block();

        // 用独立 intent: 前缀 Agent，不碰会话 Agent
        verify(factory).createAgent("intent:u7");
        verify(factory, org.mockito.Mockito.never()).createAgent("u7");
    }

    /** chat 失败走兜底时应递增 customerwork.chat.fallback 计数。 */
    @Test
    void chat_shouldIncrementFallbackMetric_whenAgentFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        service.setMeterRegistry(registry);
        when(agent.call(anyString(), any(RuntimeContext.class)))
            .thenReturn(Mono.error(new RuntimeException("model down")));

        service.chat("m1", "查询订单").block();

        org.junit.jupiter.api.Assertions.assertEquals(1.0,
            registry.counter("customerwork.chat.fallback").count(), "chat 兜底应计数");
    }

    /** 意图分类失败时应递增 customerwork.intent.classify.errors 计数。 */
    @Test
    void classifyIntent_shouldIncrementErrorMetric_whenParsingFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        service.setMeterRegistry(registry);
        when(agent.call(anyString(), eq(IntentResult.class), any(RuntimeContext.class)))
            .thenReturn(Mono.error(new RuntimeException("bad json")));

        service.classifyIntent("m2", "随便").block();

        org.junit.jupiter.api.Assertions.assertEquals(1.0,
            registry.counter("customerwork.intent.classify.errors").count(), "意图失败应计数");
    }

    /** 未注入 MeterRegistry 时计数降级为 no-op，不应抛异常。 */
    @Test
    void chat_shouldNotThrow_whenNoMeterRegistry() {
        when(agent.call(anyString(), any(RuntimeContext.class)))
            .thenReturn(Mono.error(new RuntimeException("model down")));

        StepVerifier.create(service.chat("m3", "hi"))
            .assertNext(reply -> org.junit.jupiter.api.Assertions.assertTrue(reply.contains("系统繁忙")))
            .verifyComplete();
    }

    /**
     * SSE 空闲超时：用一个永不产元素的流 + 1 秒超时，断言超时后收到兜底收尾消息而非挂死。
     */
    @Test
    void chatStream_shouldEmitFallback_onIdleTimeout() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getStream().setIdleTimeoutSeconds(1);
        CustomerServiceService timedService =
            new CustomerServiceService(factory, sessionStateManager, props);
        // 永不产元素、也不完成的流
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.never());

        StepVerifier.create(timedService.chatStream("s1", "你好"))
            .assertNext(chunk -> org.junit.jupiter.api.Assertions.assertTrue(chunk.contains("系统繁忙")))
            .thenAwait(Duration.ofSeconds(2))
            .verifyComplete();
    }

    /** idleTimeoutSeconds<=0 时禁用超时：正常流不受影响。 */
    @Test
    void chatStream_shouldNotTimeout_whenDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getStream().setIdleTimeoutSeconds(0);
        CustomerServiceService noTimeoutService =
            new CustomerServiceService(factory, sessionStateManager, props);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new AgentResultEvent(assistantMsg("好"))));

        StepVerifier.create(noTimeoutService.chatStream("s2", "你好"))
            .expectNext("好")
            .verifyComplete();
    }

    // ======== 会话级并发控制测试 ========

    /**
     * 同一 sessionId 的并发请求应串行执行：第一个未完成时第二个不应开始。
     */
    @Test
    void chat_shouldSerializeConcurrentRequests_forSameSession() {
        java.util.concurrent.atomic.AtomicInteger executionOrder = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger overlapCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger(0);

        when(agent.call(anyString(), any(RuntimeContext.class))).thenAnswer(inv -> {
            int current = inFlight.incrementAndGet();
            if (current > 1) {
                overlapCount.incrementAndGet();
            }
            executionOrder.incrementAndGet();
            // 递减必须用 doOnTerminate（终止信号传播前执行）而非 doFinally（传播后执行）：
            // 服务端 withSessionLock 的 lock.release() 也挂在 doFinally 上且位于下游，传播序决定
            // "先 release、后递减"——两者之间的窗口里，第二个请求在慢机器上可能抢到锁先执行
            // increment，造成串行化成立却计数误报重叠（CI 2 核环境偶发红）。
            return Mono.defer(() -> Mono.just(assistantMsg("ok")))
                .delayElement(Duration.ofMillis(100))
                .doOnTerminate(inFlight::decrementAndGet);
        });

        // 并发发起两个同一会话的请求
        Mono.zip(
            service.chat("concurrent", "msg1").subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),
            service.chat("concurrent", "msg2").subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        ).block(Duration.ofSeconds(5));

        org.junit.jupiter.api.Assertions.assertEquals(0, overlapCount.get(),
            "同一会话的并发请求不应重叠执行");
    }

    /**
     * 不同 sessionId 的请求可并行执行（锁粒度是会话级，不是全局）。
     */
    @Test
    void chat_shouldAllowConcurrentRequests_forDifferentSessions() {
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger(0);

        when(agent.call(anyString(), any(RuntimeContext.class))).thenAnswer(inv -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            return Mono.just(assistantMsg("ok"))
                .delayElement(Duration.ofMillis(100))
                .doFinally(s -> inFlight.decrementAndGet());
        });

        Mono.zip(
            service.chat("session-a", "msg1").subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),
            service.chat("session-b", "msg2").subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        ).block(Duration.ofSeconds(5));

        org.junit.jupiter.api.Assertions.assertTrue(maxInFlight.get() >= 2,
            "不同会话的请求应可并行执行，maxInFlight=" + maxInFlight.get());
    }

    /** endSession 应清理会话锁，使后续请求不被旧锁阻塞。 */
    @Test
    void endSession_shouldCleanUpLock() {
        when(agent.call(anyString(), any(RuntimeContext.class)))
            .thenReturn(Mono.just(assistantMsg("ok")));

        service.chat("lock-test", "hi").block();
        service.endSession("lock-test");
        // 结束后再次对话应正常工作（锁已清理，不会死锁）
        StepVerifier.create(service.chat("lock-test", "again"))
            .expectNext("ok")
            .verifyComplete();
    }
}
