package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
            Mockito.mock(CustomerServiceAgentFactory.class), new CustomerWorkProperties());

        RunAgentInput input = service.buildInput("tenantA:conv-1", "查询订单");

        assertEquals("tenantA:conv-1", input.getThreadId());
        assertFalse(input.getRunId().isBlank(), "应生成 runId");
        assertEquals(1, input.getMessages().size(), "应携带一条用户消息");
    }
}
