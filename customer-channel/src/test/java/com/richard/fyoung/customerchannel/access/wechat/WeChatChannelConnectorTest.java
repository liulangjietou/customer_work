package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelInboundMessage;
import com.richard.fyoung.customerchannel.access.model.ChannelReplySender;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link WeChatChannelConnector} 测试：MsgId 去重、归一化消息构造、客服消息回复器降级+推送、start/stop 注册表登记。
 * @author owlzhangfq@gmail.com
 */
class WeChatChannelConnectorTest {

    private static final String APP_ID = "wx-app-1";
    private static final String SECRET = "wx-secret-1";
    private static final String TOKEN = "callback-token-1";
    private static final String AGENT = "agent-x";
    private static final String OPENID = "openid-42";

    private ChannelMessagePipeline pipeline;
    private WeChatConnectorRegistry registry;
    private WeChatAccessTokenClient tokenClient;
    private WeChatCustomerMessageSender sender;
    private WeChatChannelConnector connector;

    @BeforeEach
    void setUp() {
        pipeline = mock(ChannelMessagePipeline.class);
        registry = new WeChatConnectorRegistry();
        tokenClient = mock(WeChatAccessTokenClient.class);
        sender = mock(WeChatCustomerMessageSender.class);
        ChannelRobot robot = new ChannelRobot(1L, ChannelAccessConstants.CHANNEL_TYPE_WECHAT, "微信客服",
            APP_ID, SECRET, TOKEN, AGENT, ChannelAccessConstants.SESSION_MODE_CONTINUOUS, 100L);
        connector = new WeChatChannelConnector(robot, pipeline, registry, tokenClient, sender);
    }

    private WeChatXmlMessage textMessage(String content, String msgId) {
        String xml = "<xml>"
            + "<FromUserName><![CDATA[" + OPENID + "]]></FromUserName>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[" + content + "]]></Content>"
            + "<MsgId>" + msgId + "</MsgId>"
            + "</xml>";
        return WeChatXmlMessage.parse(xml);
    }

    @Test
    void shouldExposeCallbackToken() {
        assertEquals(TOKEN, connector.callbackToken());
    }

    @Test
    void shouldSubmitNormalizedMessageAndWireReplySender() {
        connector.dispatch(textMessage("你好", "msg-1"));

        ArgumentCaptor<ChannelInboundMessage> msgCaptor = ArgumentCaptor.forClass(ChannelInboundMessage.class);
        ArgumentCaptor<ChannelReplySender> replyCaptor = ArgumentCaptor.forClass(ChannelReplySender.class);
        verify(pipeline).submit(msgCaptor.capture(), replyCaptor.capture());

        ChannelInboundMessage inbound = msgCaptor.getValue();
        assertEquals(ChannelAccessConstants.CHANNEL_TYPE_WECHAT, inbound.getChannelType());
        assertEquals(APP_ID, inbound.getAppKey());
        assertEquals(AGENT, inbound.getAgentCode());
        assertEquals(OPENID, inbound.getExternalUserId());
        assertTrue(inbound.isText());
        assertEquals("你好", inbound.getContent());

        // 回复器：markdown 降级为纯文本后经客服消息推送给 openid
        replyCaptor.getValue().send("**加粗**回复");
        verify(sender).send(eq(APP_ID), eq(SECRET), eq(OPENID), eq("加粗回复"));
    }

    @Test
    void shouldDeduplicateByMsgId() {
        connector.dispatch(textMessage("你好", "dup-1"));
        connector.dispatch(textMessage("你好", "dup-1"));

        // 相同 MsgId 只投递一次
        verify(pipeline, times(1)).submit(any(), any());
    }

    @Test
    void shouldRegisterAndUnregisterOnStartStop() {
        connector.start();
        assertEquals(connector, registry.find(APP_ID));
        assertTrue(connector.isRunning());

        connector.stop();
        assertEquals(null, registry.find(APP_ID));
        assertTrue(!connector.isRunning());
    }
}
