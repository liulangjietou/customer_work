package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelAccessProperties;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 微信回调测试：时间窗、明文/安全模式、nonce 回放、消息幂等与存储故障 fail-closed。 */
class WeChatCallbackControllerTest {

    private static final String APP_ID = "wx-app-1";
    private static final String TOKEN = "callback-token-1";
    private static final String TIMESTAMP = "1700000000";
    private static final String NONCE = "nonce-abc";
    private static final byte[] AES_KEY = createAesKey();
    private static final String ENCODING_AES_KEY = Base64.getEncoder().withoutPadding().encodeToString(AES_KEY);

    private WeChatConnectorRegistry registry;
    private ChannelMessagePipeline pipeline;
    private ChannelAccessProperties properties;
    private WeChatCallbackController controller;

    @BeforeEach
    void setUp() {
        registry = new WeChatConnectorRegistry();
        pipeline = mock(ChannelMessagePipeline.class);
        properties = new ChannelAccessProperties();
        registerConnector(ChannelAccessConstants.WECHAT_CALLBACK_MODE_PLAINTEXT, null);
        controller = controller(new InMemoryWeChatReplayGuard(100));
    }

    @Test
    void getShouldEchoOnValidSignature() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = controller.verify(APP_ID, sign(TOKEN, TIMESTAMP, NONCE), null,
            TIMESTAMP, NONCE, "echo-123", response);

        assertEquals("echo-123", output);
        assertEquals(200, response.getStatus());
    }

    @Test
    void getShouldRejectStaleTimestampBeforeSignatureAcceptance() throws Exception {
        String stale = "1699999000";
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = controller.verify(APP_ID, sign(TOKEN, stale, NONCE), null,
            stale, NONCE, "echo-123", response);

        assertEquals("", output);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getShouldReturn403OnBadSignature() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = controller.verify(APP_ID, "badsig", null,
            TIMESTAMP, NONCE, "echo-123", response);

        assertEquals("", output);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getShouldReturn404OnUnknownAppId() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("unknown-app", "sig", null, TIMESTAMP, NONCE, "echo", response);

        assertEquals(404, response.getStatus());
    }

    @Test
    void postShouldReturnSuccessAndDispatchOnValidSignature() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = controller.receive(APP_ID, sign(TOKEN, TIMESTAMP, NONCE), null,
            TIMESTAMP, NONCE, textXml("m-1"), response);

        assertEquals(ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS, output);
        assertEquals(200, response.getStatus());
        verify(pipeline).submit(any(), any());
    }

    @Test
    void postShouldSuppressDuplicateNonce() throws Exception {
        String signature = sign(TOKEN, TIMESTAMP, NONCE);

        controller.receive(APP_ID, signature, null, TIMESTAMP, NONCE,
            textXml("m-1"), new MockHttpServletResponse());
        String duplicate = controller.receive(APP_ID, signature, null, TIMESTAMP, NONCE,
            textXml("m-1"), new MockHttpServletResponse());

        assertEquals(ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS, duplicate);
        verify(pipeline, times(1)).submit(any(), any());
    }

    @Test
    void postShouldSuppressSameMessageRetriedWithDifferentNonce() throws Exception {
        String nextNonce = "nonce-def";

        controller.receive(APP_ID, sign(TOKEN, TIMESTAMP, NONCE), null,
            TIMESTAMP, NONCE, textXml("m-1"), new MockHttpServletResponse());
        controller.receive(APP_ID, sign(TOKEN, TIMESTAMP, nextNonce), null,
            TIMESTAMP, nextNonce, textXml("m-1"), new MockHttpServletResponse());

        verify(pipeline, times(1)).submit(any(), any());
    }

    @Test
    void postShouldReturn403AndNotDispatchOnBadSignature() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = controller.receive(APP_ID, "badsig", null,
            TIMESTAMP, NONCE, textXml("m-1"), response);

        assertEquals("", output);
        assertEquals(403, response.getStatus());
        verify(pipeline, never()).submit(any(), any());
    }

    @Test
    void postShouldStillReturnSuccessOnInvalidPlaintextXml() throws Exception {
        String output = controller.receive(APP_ID, sign(TOKEN, TIMESTAMP, NONCE), null,
            TIMESTAMP, NONCE, "broken<<", new MockHttpServletResponse());

        assertEquals(ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS, output);
        verify(pipeline, never()).submit(any(), any());
    }

    @Test
    void replayStoreFailureShouldReturn503WithoutDispatch() throws Exception {
        WeChatReplayGuard unavailable = new WeChatReplayGuard() {
            @Override
            public boolean claimNonce(String appId, String nonce, Duration ttl) {
                throw new IllegalStateException("redis unavailable");
            }

            @Override
            public boolean claimMessage(String appId, String messageKey, Duration ttl) {
                throw new IllegalStateException("redis unavailable");
            }
        };
        WeChatCallbackController failClosed = controller(unavailable);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = failClosed.receive(APP_ID, sign(TOKEN, TIMESTAMP, NONCE), null,
            TIMESTAMP, NONCE, textXml("m-1"), response);

        assertEquals("", output);
        assertEquals(503, response.getStatus());
        verify(pipeline, never()).submit(any(), any());
    }

    @Test
    void safeGetShouldVerifySignatureAndDecryptEcho() throws Exception {
        registerConnector(ChannelAccessConstants.WECHAT_CALLBACK_MODE_SAFE, ENCODING_AES_KEY);
        String encryptedEcho = encrypt("echo-safe", APP_ID);
        String messageSignature = sign(TOKEN, TIMESTAMP, NONCE, encryptedEcho);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = controller.verify(APP_ID, null, messageSignature,
            TIMESTAMP, NONCE, encryptedEcho, response);

        assertEquals("echo-safe", output);
        assertEquals(200, response.getStatus());
    }

    @Test
    void safePostShouldDecryptAndDispatch() throws Exception {
        registerConnector(ChannelAccessConstants.WECHAT_CALLBACK_MODE_SAFE, ENCODING_AES_KEY);
        String encrypted = encrypt(textXml("safe-m-1"), APP_ID);
        String outerXml = "<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>";

        String output = controller.receive(APP_ID, null, sign(TOKEN, TIMESTAMP, NONCE, encrypted),
            TIMESTAMP, NONCE, outerXml, new MockHttpServletResponse());

        assertEquals(ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS, output);
        verify(pipeline).submit(any(), any());
    }

    @Test
    void safePostShouldRejectCiphertextForAnotherAppId() throws Exception {
        registerConnector(ChannelAccessConstants.WECHAT_CALLBACK_MODE_SAFE, ENCODING_AES_KEY);
        String encrypted = encrypt(textXml("safe-m-1"), "wx-other-app");
        String outerXml = "<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>";
        MockHttpServletResponse response = new MockHttpServletResponse();

        String output = controller.receive(APP_ID, null, sign(TOKEN, TIMESTAMP, NONCE, encrypted),
            TIMESTAMP, NONCE, outerXml, response);

        assertEquals("", output);
        assertEquals(403, response.getStatus());
        verify(pipeline, never()).submit(any(), any());
    }

    private WeChatCallbackController controller(WeChatReplayGuard replayGuard) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)), ZoneOffset.UTC);
        return new WeChatCallbackController(registry, replayGuard, properties, clock);
    }

    private void registerConnector(String callbackMode, String encodingAesKey) {
        ChannelRobot robot = new ChannelRobot(1L, ChannelAccessConstants.CHANNEL_TYPE_WECHAT, "微信客服",
            APP_ID, "secret", TOKEN, "agent-x", ChannelAccessConstants.SESSION_MODE_CONTINUOUS,
            callbackMode, encodingAesKey, 1L);
        WeChatChannelConnector connector = new WeChatChannelConnector(robot, pipeline, registry,
            mock(WeChatAccessTokenClient.class), mock(WeChatCustomerMessageSender.class));
        registry.register(APP_ID, connector);
    }

    private static String textXml(String msgId) {
        return "<xml>"
            + "<FromUserName><![CDATA[openid-1]]></FromUserName>"
            + "<CreateTime>" + TIMESTAMP + "</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[你好]]></Content>"
            + "<MsgId>" + msgId + "</MsgId>"
            + "</xml>";
    }

    private static String sign(String... parts) throws Exception {
        String[] sorted = Arrays.copyOf(parts, parts.length);
        Arrays.sort(sorted);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(String.join("", sorted).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private static String encrypt(String payload, String appId) throws Exception {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] appIdBytes = appId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer plain = ByteBuffer.allocate(16 + 4 + payloadBytes.length + appIdBytes.length + 32);
        plain.put(new byte[16]);
        plain.putInt(payloadBytes.length);
        plain.put(payloadBytes);
        plain.put(appIdBytes);
        int contentLength = plain.position();
        int padding = 32 - contentLength % 32;
        for (int i = 0; i < padding; i++) {
            plain.put((byte) padding);
        }
        byte[] padded = Arrays.copyOf(plain.array(), plain.position());
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
            new IvParameterSpec(AES_KEY, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static byte[] createAesKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }
}
