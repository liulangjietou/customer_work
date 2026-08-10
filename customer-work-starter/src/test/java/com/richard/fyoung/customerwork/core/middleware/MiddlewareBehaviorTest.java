package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.SensitiveDataMasker;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

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
