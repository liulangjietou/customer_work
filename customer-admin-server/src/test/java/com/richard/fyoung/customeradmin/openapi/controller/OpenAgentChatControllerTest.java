package com.richard.fyoung.customeradmin.openapi.controller;

import com.richard.fyoung.customeradmin.openapi.dto.OpenChatRequest;
import com.richard.fyoung.customeradmin.openapi.service.OpenChannelService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OpenAgentChatController} SSE 换行安全契约测试：message/error 事件 data 为 JSON 字符串字面量
 * （换行被转义进字面量、不裸露在帧里），done 事件仍为固定 {@code [DONE]}。
 * @author owlzhangfq@gmail.com
 */
class OpenAgentChatControllerTest {

    private static final String AGENT = "agent-x";
    private static final String CHANNEL = "dingtalk";
    private static final String APP_KEY = "app-key-1";

    private ChatService chatService;
    private OpenChannelService openChannelService;
    private OpenAgentChatController controller;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        openChannelService = mock(OpenChannelService.class);
        controller = new OpenAgentChatController(chatService, openChannelService);
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(
            "default", QuotaSubjectType.API_KEY, "api-key-fingerprint", true));
    }

    @AfterEach
    void tearDown() {
        AgentInvocationIdentityContext.clear();
        TenantContext.clear();
    }

    @Test
    void messageDataShouldBeJsonStringLiteralWithEscapedNewline() {
        when(chatService.chatStreamForChannel(eq(AGENT), any(), any(), eq(CHANNEL)))
            .thenReturn(Flux.just(new ChatStreamChunk(ChatNodeKind.ANSWER, "a\nb")));

        List<ServerSentEvent<String>> events =
            controller.chat(AGENT, request()).collectList().block();

        assertEquals(2, events.size());
        assertEquals("message", events.get(0).event());
        // JSON 字面量："a\nb" 中的换行转义为 \n（不含真实换行字符）
        assertEquals("\"a\\nb\"", events.get(0).data());
        assertEquals("done", events.get(1).event());
        assertEquals("[DONE]", events.get(1).data());
        verify(openChannelService).requireChatAuthorized(AGENT, CHANNEL, APP_KEY, "user-1", "s1");
    }

    @Test
    void errorDataShouldBeJsonStringLiteral() {
        doThrow(new RuntimeException("boom")).when(openChannelService)
            .requireChatAuthorized(AGENT, CHANNEL, APP_KEY, "user-1", "s1");

        List<ServerSentEvent<String>> events =
            controller.chat(AGENT, request()).collectList().block();

        assertEquals(1, events.size());
        assertEquals("error", events.get(0).event());
        assertEquals("\"boom\"", events.get(0).data());
        verify(chatService, never()).chatStreamForChannel(any(), any(), any(), any());
    }

    @Test
    void shouldCarryCapturedTenantIntoDeferredChatSubscription() {
        TenantContext.set("tenant-a");
        when(chatService.chatStreamForChannel(eq(AGENT), any(), any(), eq(CHANNEL)))
            .thenReturn(Flux.deferContextual(context -> Flux.just(
                new ChatStreamChunk(ChatNodeKind.ANSWER, tenantFrom(context)))));

        Flux<ServerSentEvent<String>> result = controller.chat(AGENT, request());
        TenantContext.clear();
        List<ServerSentEvent<String>> events = result.collectList().block();

        assertEquals("\"tenant-a\"", events.get(0).data());
    }

    private String tenantFrom(ContextView context) {
        return context.get(TenantContextThreadLocalAccessor.KEY);
    }

    private OpenChatRequest request() {
        return new OpenChatRequest("s1", "hi", CHANNEL, APP_KEY, "user-1");
    }
}
