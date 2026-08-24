package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 统一终止信封的 usage 去重、累计与 finishReason 口径测试。 */
class ChatTerminalCaptureTest {

    @Test
    void envelope_shouldAggregateUniqueModelCallsAndKeepAgentFinishReason() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        capture.accept(new ModelCallEndEvent("reply-1", new ChatUsage(10, 2, 1, 0.3)));
        capture.accept(new ModelCallEndEvent("reply-1", new ChatUsage(10, 2, 1, 0.3)));
        capture.accept(new ModelCallEndEvent("reply-2", new ChatUsage(5, 3, 0, 0.2)));
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("partial")
            .generateReason(GenerateReason.MAX_ITERATIONS).build();
        capture.accept(new AgentResultEvent(result));

        ChatTerminalEnvelope terminal = capture.envelope("MSG-1", "partial", "trace-1");

        assertThat(terminal.finishReason()).isEqualTo("MAX_ITERATIONS");
        assertThat(terminal.usage().inputTokens()).isEqualTo(15);
        assertThat(terminal.usage().outputTokens()).isEqualTo(5);
        assertThat(terminal.usage().cachedTokens()).isEqualTo(1);
        assertThat(terminal.usage().totalTokens()).isEqualTo(20);
        assertThat(terminal.usage().timeSeconds()).isEqualTo(0.5);
    }

    @Test
    void envelope_shouldUseExplicitReasonsForNonAgentTerminalPaths() {
        assertThat(new ChatTerminalCapture().envelope("MSG-1",
            CustomerServiceService.QUOTA_EXCEEDED_REPLY, "t").finishReason())
            .isEqualTo(ChatTerminalCapture.QUOTA_EXCEEDED);
        assertThat(new ChatTerminalCapture().envelope("MSG-2",
            CustomerServiceService.FALLBACK_REPLY, "t").finishReason())
            .isEqualTo(ChatTerminalCapture.ERROR);
        assertThat(new ChatTerminalCapture().envelope("MSG-3", "cached", "t").finishReason())
            .isEqualTo(ChatTerminalCapture.CACHE_HIT);
    }
}
