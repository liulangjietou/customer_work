package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTurnFinalizer;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AG-UI 协议单测（交互协议）：消息转换与运行输入构造（离线，不触达模型）。
 * @author owlzhangfq@gmail.com
 */
class AguiServiceTest {

    @Test
    void messageConverter_shouldRoundtripUserMessage() {
        AguiMessageConverter converter = new AguiMessageConverter();
        Msg msg = Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text("你好 AG-UI").build()).build();

        AguiMessage agui = converter.toAguiMessage(msg);
        Msg back = converter.toMsg(agui);

        assertTrue(back.getTextContent().contains("你好 AG-UI"), "AG-UI 消息往返应保留文本");
    }

    @Test
    void buildInput_shouldCarrySessionAndMessage() {
        AguiService service = new AguiService(
            Mockito.mock(CustomerServiceAgentFactory.class), new CustomerWorkProperties(),
            Mockito.mock(ChatTurnFinalizer.class));

        RunAgentInput input = service.buildInput("tenantA:conv-1", "查询订单");

        assertEquals("tenantA:conv-1", input.getThreadId());
        assertFalse(input.getRunId().isBlank(), "应生成 runId");
        assertEquals(1, input.getMessages().size(), "应携带一条用户消息");
    }

    @Test
    void runFinished_shouldPersistReplyAndCarryUnifiedTerminalEnvelope() {
        InMemoryChatMessageStore store = new InMemoryChatMessageStore();
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(new ChatLogService(store));
        AguiService service = new AguiService(
            Mockito.mock(CustomerServiceAgentFactory.class), new CustomerWorkProperties(), finalizer);
        ChatTerminalCapture capture = new ChatTerminalCapture();
        HashMap<String, StringBuilder> buffers = new HashMap<>();
        AtomicReference<String> latest = new AtomicReference<>();

        service.finalizeEvent("s1", new AguiEvent.TextMessageContent("s1", "run-1", "a1", "您"),
            capture, "trace-agui", buffers, latest).block();
        service.finalizeEvent("s1", new AguiEvent.TextMessageContent("s1", "run-1", "a1", "好"),
            capture, "trace-agui", buffers, latest).block();
        AguiEvent result = service.finalizeEvent("s1",
            new AguiEvent.RunFinished("s1", "run-1"), capture, "trace-agui", buffers, latest).block();

        assertTrue(result instanceof AguiEvent.RunFinished);
        ChatTerminalEnvelope terminal = (ChatTerminalEnvelope) ((AguiEvent.RunFinished) result).result();
        assertEquals("trace-agui", terminal.traceId());
        assertEquals("CACHE_HIT", terminal.finishReason());
        assertTrue(store.findByMessageId(terminal.messageId()).isPresent(), "终止前必须已完成消息持久化");
        assertEquals("您好", store.findByMessageId(terminal.messageId()).orElseThrow().content());
    }
}
