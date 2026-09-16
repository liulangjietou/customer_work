package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCaptureContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** 治理装配中的终止事件采集测试。 */
class ChatTerminalCaptureMiddlewareTest {

    @Test
    void capture_shouldBeBoundToTheNativeInvocationForBlockingTools() {
        var context = RuntimeContext.builder().userId("u-1").sessionId("s-1").build();
        ChatTerminalCapture capture = new ChatTerminalCapture();
        var observed = new AtomicReference<ChatTerminalCapture>();
        new ChatTerminalCaptureMiddleware().onAgent(null, context, null, ignored -> {
            observed.set(context.get(ChatTerminalCapture.class));
            return Flux.empty();
        }).contextWrite(view -> ChatTerminalCaptureContext.withCapture(view, capture)).blockLast();
        assertThat(observed.get()).isSameAs(capture);
        assertThat(context.get(ChatTerminalCapture.class)).isNull();
    }

    @Test
    void synchronousFailure_shouldRestoreTheEnclosingNativeCapture() {
        var context = RuntimeContext.builder().sessionId("nested").build();
        var enclosing = new ChatTerminalCapture();
        var current = new ChatTerminalCapture();
        context.put(ChatTerminalCapture.class, enclosing);
        StepVerifier.create(new ChatTerminalCaptureMiddleware().onAgent(null, context, null, ignored -> {
            throw new IllegalStateException("failed before events");
        }).contextWrite(view -> ChatTerminalCaptureContext.withCapture(view, current)))
            .expectErrorMessage("failed before events").verify();
        assertThat(context.get(ChatTerminalCapture.class)).isSameAs(enclosing);
        assertThat(current.envelope("m1", "partial", "trace").finishReason()).isEqualTo("ERROR");
    }

    @Test
    void onAgent_shouldCaptureUsageAndFinishReasonFromReactorContext() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("ok")
            .generateReason(GenerateReason.MODEL_STOP).build();
        Flux<AgentEvent> events = Flux.just(
            new ModelCallEndEvent("r1", new ChatUsage(7, 3, 1, 0.4)),
            new AgentResultEvent(result));

        StepVerifier.create(new ChatTerminalCaptureMiddleware()
                .onAgent(null, null, null, ignored -> events)
                .contextWrite(context -> ChatTerminalCaptureContext.withCapture(context, capture)))
            .expectNextCount(2)
            .verifyComplete();

        assertThat(capture.envelope("MSG-1", "ok", "trace-1").finishReason())
            .isEqualTo("MODEL_STOP");
        assertThat(capture.usage().totalTokens()).isEqualTo(10);
    }
}
