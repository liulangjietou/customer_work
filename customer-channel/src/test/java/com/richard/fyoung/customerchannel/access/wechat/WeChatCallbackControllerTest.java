package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link WeChatCallbackController} 测试：GET 验签回显/403、未注册 appId 404、POST 立即 success + 分发、验签失败 403。
 * @author owlzhangfq@gmail.com
 */
class WeChatCallbackControllerTest {

    private static final String APP_ID = "wx-app-1";
    private static final String TOKEN = "callback-token-1";
    private static final String TIMESTAMP = "1700000000";
    private static final String NONCE = "nonce-abc";

    private WeChatConnectorRegistry registry;
    private ChannelMessagePipeline pipeline;
    private WeChatCallbackController controller;

    @BeforeEach
    void setUp() {
        registry = new WeChatConnectorRegistry();
        pipeline = mock(ChannelMessagePipeline.class);
        WeChatAccessTokenClient tokenClient = mock(WeChatAccessTokenClient.class);
        WeChatCustomerMessageSender sender = mock(WeChatCustomerMessageSender.class);
        ChannelRobot robot = new ChannelRobot(1L, ChannelAccessConstants.CHANNEL_TYPE_WECHAT, "微信客服",
            APP_ID, "secret", TOKEN, "agent-x", ChannelAccessConstants.SESSION_MODE_CONTINUOUS, 1L);
        WeChatChannelConnector connector =
            new WeChatChannelConnector(robot, pipeline, registry, tokenClient, sender);
        registry.register(APP_ID, connector);
        controller = new WeChatCallbackController(registry);
    }

    private static String sign(String token, String timestamp, String nonce) throws Exception {
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest((arr[0] + arr[1] + arr[2]).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String textXml() {
        return "<xml>"
            + "<FromUserName><![CDATA[openid-1]]></FromUserName>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[你好]]></Content>"
            + "<MsgId>m-1</MsgId>"
            + "</xml>";
    }

    @Test
    void getShouldEchoOnValidSignature() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        String sig = sign(TOKEN, TIMESTAMP, NONCE);

        String out = controller.verify(APP_ID, sig, TIMESTAMP, NONCE, "echo-123", resp);

        assertEquals("echo-123", out);
        assertEquals(200, resp.getStatus());
    }

    @Test
    void getShouldReturn403OnBadSignature() {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        String out = controller.verify(APP_ID, "badsig", TIMESTAMP, NONCE, "echo-123", resp);

        assertEquals("", out);
        assertEquals(403, resp.getStatus());
    }

    @Test
    void getShouldReturn404OnUnknownAppId() {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.verify("unknown-app", "sig", TIMESTAMP, NONCE, "echo", resp);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void postShouldReturnSuccessAndDispatchOnValidSignature() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        String sig = sign(TOKEN, TIMESTAMP, NONCE);

        String out = controller.receive(APP_ID, sig, TIMESTAMP, NONCE, textXml(), resp);

        assertEquals(ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS, out);
        assertEquals(200, resp.getStatus());
        verify(pipeline, times(1)).submit(any(), any());
    }

    @Test
    void postShouldReturn403AndNotDispatchOnBadSignature() {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        String out = controller.receive(APP_ID, "badsig", TIMESTAMP, NONCE, textXml(), resp);

        assertEquals("", out);
        assertEquals(403, resp.getStatus());
        verify(pipeline, never()).submit(any(), any());
    }

    @Test
    void postShouldReturn404OnUnknownAppId() {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.receive("unknown-app", "sig", TIMESTAMP, NONCE, textXml(), resp);

        assertEquals(404, resp.getStatus());
        verify(pipeline, never()).submit(any(), any());
    }

    @Test
    void postShouldStillReturnSuccessOnInvalidXml() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        String sig = sign(TOKEN, TIMESTAMP, NONCE);

        String out = controller.receive(APP_ID, sig, TIMESTAMP, NONCE, "broken<<", resp);

        // 非法 XML 记日志后仍回 success，避免微信重试风暴；不分发
        assertEquals(ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS, out);
        verify(pipeline, never()).submit(any(), any());
    }
}
