package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.SensitiveDataMasker;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Middleware 行为单测：脱敏 / 自我纠错检测 / 人工确认 / 护栏放行的核心逻辑。
 * @author owlzhangfq@gmail.com
 */
class MiddlewareBehaviorTest {

    private Msg assistant(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
            .content(TextBlock.builder().text(text).build()).build();
    }

    // ---------- MaskingMiddleware ----------
    @Test
    void masking_shouldMaskOutboundResultText() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(true);
        SensitiveDataMasker masker = mock(SensitiveDataMasker.class);
        when(masker.hasRules()).thenReturn(true);
        when(masker.mask("您的手机号 13800138000")).thenReturn("您的手机号 138****8000");

        MaskingMiddleware mw = new MaskingMiddleware(props, masker);
        Msg masked = mw.maskMessage(assistant("您的手机号 13800138000"));

        assertTrue(masked.getTextContent().contains("138****8000"), "敏感号码应被掩码");
    }

    @Test
    void masking_onAgent_shouldReplaceAgentResultEvent() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(true);
        SensitiveDataMasker masker = mock(SensitiveDataMasker.class);
        when(masker.hasRules()).thenReturn(true);
        when(masker.mask("卡号 6222021234567890123")).thenReturn("卡号 ****");

        MaskingMiddleware mw = new MaskingMiddleware(props, masker);
        AgentEvent out = mw.onAgent(null, null, new AgentInput(List.of()),
            in -> Flux.just(new AgentResultEvent(assistant("卡号 6222021234567890123"))))
            .blockLast();

        assertTrue(((AgentResultEvent) out).getResult().getTextContent().contains("****"));
    }

    @Test
    void masking_stream_shouldNotEmitRawPhoneSplitAcrossDeltas() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(true);
        MaskingMiddleware middleware = new MaskingMiddleware(props, new SensitiveDataMasker(props));
        TestPublisher<AgentEvent> upstream = TestPublisher.create();

        StepVerifier.create(middleware.onAgent(null, null, new AgentInput(List.of()),
                input -> upstream.flux()))
            .then(() -> upstream.next(new TextBlockDeltaEvent("reply-1", "block-1", "手机号 13800")))
            .expectNoEvent(Duration.ofMillis(20))
            .then(() -> upstream.next(
                new TextBlockDeltaEvent("reply-1", "block-1", "138000"),
                new TextBlockEndEvent("reply-1", "block-1")).complete())
            .assertNext(event -> {
                assertTrue(event instanceof TextBlockDeltaEvent);
                String delta = ((TextBlockDeltaEvent) event).getDelta();
                assertTrue(delta.contains("***"));
                assertFalse(delta.contains("13800138000"), "跨 chunk 手机号不得出现在任何出站 delta");
            })
            .assertNext(event -> assertTrue(event instanceof TextBlockEndEvent))
            .verifyComplete();
    }

    @Test
    void masking_stream_shouldFlushCrossChunkEmail_whenBlockEndIsMissing() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(true);
        MaskingMiddleware middleware = new MaskingMiddleware(props, new SensitiveDataMasker(props));

        List<AgentEvent> output = middleware.onAgent(null, null, new AgentInput(List.of()), input -> Flux.just(
                new TextBlockDeltaEvent("reply-2", "block-2", "邮箱 user.name@"),
                new TextBlockDeltaEvent("reply-2", "block-2", "example.com 已记录")))
            .collectList().block();

        assertTrue(output != null && output.size() == 1);
        String delta = ((TextBlockDeltaEvent) output.get(0)).getDelta();
        assertTrue(delta.contains("邮箱 *** 已记录"), "流完成必须 flush 并脱敏跨 chunk 邮箱");
        assertFalse(delta.contains("user.name@example.com"));
    }

    // ---------- SelfCorrectionMiddleware ----------
    @Test
    void selfCorrection_shouldDetectPaymentPromise() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getSelfCorrection().setEnabled(true);
        SelfCorrectionMiddleware mw = new SelfCorrectionMiddleware(props);

        assertTrue(mw.promisesPayment("已为您退款到账"));
        assertFalse(mw.promisesPayment("您好，请提供订单号"));
    }

    // ---------- HumanApprovalMiddleware ----------
    @Test
    void humanApproval_shouldFlagGuardedTool_andPassThrough() {
        HumanApprovalMiddleware mw = new HumanApprovalMiddleware(Set.of("submitRefund"));
        assertTrue(mw.isGuarded("submitRefund"));
        assertFalse(mw.isGuarded("queryOrder"));

        AtomicBoolean proceeded = new AtomicBoolean(false);
        ActingInput in = new ActingInput(List.of(
            new ToolUseBlock("t1", "submitRefund", null, null)));
        mw.onActing(null, null, in, input -> {
            proceeded.set(true);
            return Flux.<AgentEvent>empty();
        }).blockLast();

        assertTrue(proceeded.get(), "观测后应放行到下游（实际闸门由 Permission 承担）");
    }

    // ---------- ObservabilityMiddleware passthrough ----------
    @Test
    void observability_onAgent_shouldPassEventsThrough() {
        ObservabilityMiddleware mw = new ObservabilityMiddleware();
        Long count = mw.onAgent(null, null, new AgentInput(List.of()),
            in -> Flux.just(new AgentResultEvent(assistant("ok")))).count().block();
        assertTrue(count != null && count == 1, "可观测中间件应透传事件不丢失");
        assertNull(null);
    }
}
