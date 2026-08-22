package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerchannel.access.model.ChannelInboundMessage;
import com.richard.fyoung.customerchannel.access.model.ChannelReplySender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelMessagePipeline} 指令解析与分支测试：/new、新会话、普通文本、非文本、异常兜底。
 *
 * <p>直接调用包级同步 {@code handle()}，mock 开放 API 客户端，捕获回复文本。</p>
 * @author owlzhangfq@gmail.com
 */
class ChannelMessagePipelineTest {

    private static final String TYPE = ChannelAccessConstants.CHANNEL_TYPE_DINGTALK;

    private AdminOpenApiClient client;
    private ChannelMessagePipeline pipeline;
    private List<String> replies;
    private ChannelReplySender reply;

    @BeforeEach
    void setUp() {
        client = mock(AdminOpenApiClient.class);
        pipeline = new ChannelMessagePipeline(client, new ChannelAccessProperties());
        replies = new ArrayList<>();
        reply = replies::add;
    }

    @AfterEach
    void tearDown() {
        pipeline.close();
    }

    private ChannelInboundMessage text(String content) {
        return new ChannelInboundMessage(TYPE, "ak1", "agentX",
            ChannelAccessConstants.SESSION_MODE_CONTINUOUS, "userA", true, content);
    }

    private ChannelInboundMessage perMessageText(String content) {
        return new ChannelInboundMessage(TYPE, "ak1", "agentX",
            ChannelAccessConstants.SESSION_MODE_PER_MESSAGE, "userA", true, content);
    }

    @Test
    void shouldReplyNonTextHint() {
        ChannelInboundMessage nonText = new ChannelInboundMessage(TYPE, "ak1", "agentX",
            ChannelAccessConstants.SESSION_MODE_CONTINUOUS, "userA", false, null);

        pipeline.handle(nonText, reply);

        assertEquals(List.of(ChannelAccessConstants.HINT_NON_TEXT), replies);
        verify(client, never()).resolveSession(TYPE, "ak1", "userA");
        verify(client, never()).chat("agentX", "s1", "x", TYPE, "ak1", 300);
    }

    @Test
    void shouldResetSessionOnSlashNew() {
        when(client.resetSession(TYPE, "ak1", "userA")).thenReturn("sNew");

        pipeline.handle(text("  /new  "), reply);

        assertEquals(List.of(ChannelAccessConstants.HINT_NEW_SESSION), replies);
        verify(client).resetSession(TYPE, "ak1", "userA");
    }

    @Test
    void shouldResetSessionOnChineseNew() {
        when(client.resetSession(TYPE, "ak1", "userA")).thenReturn("sNew");

        pipeline.handle(text("新会话"), reply);

        assertEquals(List.of(ChannelAccessConstants.HINT_NEW_SESSION), replies);
        verify(client).resetSession(TYPE, "ak1", "userA");
    }

    @Test
    void shouldResolveThenChatOnNormalText() {
        when(client.resolveSession(TYPE, "ak1", "userA")).thenReturn("s1");
        when(client.chat("agentX", "s1", "你好", TYPE, "ak1", 300)).thenReturn("你好，我是客服");

        pipeline.handle(text("你好"), reply);

        assertEquals(List.of("你好，我是客服"), replies);
        verify(client).resolveSession(TYPE, "ak1", "userA");
        verify(client).chat("agentX", "s1", "你好", TYPE, "ak1", 300);
    }

    @Test
    void shouldUseOneshotSessionAndSkipResolveInPerMessageMode() {
        when(client.chat(eq("agentX"), startsWith(ChannelAccessConstants.ONESHOT_SESSION_PREFIX),
            eq("你好"), eq(TYPE), eq("ak1"), eq(300))).thenReturn("答复");

        pipeline.handle(perMessageText("你好"), reply);

        assertEquals(List.of("答复"), replies);
        // 单次问答不经 admin 会话映射：resolve/reset 都不应被调用
        verify(client, never()).resolveSession(TYPE, "ak1", "userA");
        verify(client, never()).resetSession(TYPE, "ak1", "userA");
    }

    @Test
    void shouldHintInsteadOfResetInPerMessageMode() {
        pipeline.handle(perMessageText("/new"), reply);

        assertEquals(List.of(ChannelAccessConstants.HINT_PER_MESSAGE_NO_RESET), replies);
        verify(client, never()).resetSession(TYPE, "ak1", "userA");
    }

    @Test
    void shouldReplyErrorHintWhenChatThrows() {
        when(client.resolveSession(TYPE, "ak1", "userA")).thenReturn("s1");
        when(client.chat("agentX", "s1", "boom", TYPE, "ak1", 300))
            .thenThrow(new RuntimeException("timeout"));

        pipeline.handle(text("boom"), reply);

        assertEquals(List.of(ChannelAccessConstants.HINT_ERROR), replies);
    }
}
