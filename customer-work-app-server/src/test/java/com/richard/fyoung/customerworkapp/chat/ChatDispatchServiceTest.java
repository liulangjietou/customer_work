package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对话分发核心单测：关键词转人工不进 LLM、AI 流式桥接、坐席转发、新会话自动建单、标题回填、坐席消息离线只落库。
 * @author owlzhangfq@gmail.com
 */
class ChatDispatchServiceTest {

    private static final String USER_ID = "U1";
    private static final String SESSION_ID = "uU1:conv-1";

    private TicketService ticketService;
    private ChatLogService chatLogService;
    private CustomerServiceService customerServiceService;
    private HandoffKeywordDetector keywordDetector;
    private WsSessionRegistry registry;
    private ChatDispatchService dispatch;

    private final UserPrincipal user = new UserPrincipal(USER_ID, "alice", "Alice", TenantContext.DEFAULT);

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        chatLogService = mock(ChatLogService.class);
        customerServiceService = mock(CustomerServiceService.class);
        keywordDetector = mock(HandoffKeywordDetector.class);
        registry = mock(WsSessionRegistry.class);
        dispatch = new ChatDispatchService(ticketService, chatLogService, customerServiceService,
            keywordDetector, registry);
        // 落库统一返回一条带 messageId 的消息（AI 流式收尾需要读 messageId）
        lenient().when(chatLogService.append(any(), any(), any(), any(), any()))
            .thenReturn(ChatMessage.of("MSG-9", SESSION_ID, "TK-1", TicketActorType.BOT, null, "txt"));
    }

    private Ticket aiServing() {
        return Ticket.create("TK-1", SESSION_ID, USER_ID, null, TicketCategory.CONSULT);
    }

    private boolean typeIs(Object frame, String type) {
        return frame instanceof WsFrame && type.equals(((WsFrame) frame).type());
    }

    /** system 帧且 data 携带指定会话/工单标识（前端按此过滤跨会话通知）。 */
    private boolean systemWithSession(Object frame, String sessionId, String ticketId) {
        if (!typeIs(frame, WsFrame.TYPE_SYSTEM)) {
            return false;
        }
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) ((WsFrame) frame).data();
        return sessionId.equals(data.get("sessionId")) && ticketId.equals(data.get("ticketId"));
    }

    @Test
    void keywordHit_shouldRequestHandoff_andNotCallLlm() {
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(aiServing()));
        when(keywordDetector.hit("我要转人工")).thenReturn(true);

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "我要转人工")).verifyComplete();

        verify(ticketService).requestHandoff(eq(SESSION_ID), anyString(), eq(TicketActorType.USER), eq(USER_ID));
        verify(customerServiceService, never()).chatStream(anyString(), anyString());
        // system 通知帧必须携带会话/工单标识，供前端按当前查看会话过滤
        verify(registry).pushToUser(eq(USER_ID), argThat(f -> systemWithSession(f, SESSION_ID, "TK-1")));
    }

    @Test
    void requestHandoff_shouldPushSystemNoticeWithSessionContext() {
        when(ticketService.requestHandoff(eq(SESSION_ID), anyString(), eq(TicketActorType.USER), eq(USER_ID)))
            .thenReturn(aiServing());

        StepVerifier.create(dispatch.requestHandoff(user, SESSION_ID, "不想跟机器人聊")).verifyComplete();

        verify(registry).pushToUser(eq(USER_ID), argThat(f -> systemWithSession(f, SESSION_ID, "TK-1")));
    }

    @Test
    void aiServing_shouldBridgeChatStreamToChunkAndDoneFrames() {
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(aiServing()));
        when(keywordDetector.hit(anyString())).thenReturn(false);
        when(customerServiceService.chatStream(SESSION_ID, "你好")).thenReturn(Flux.just("你", "好"));

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好")).verifyComplete();

        verify(registry, times(2)).pushToUser(eq(USER_ID), argThat(f -> typeIs(f, WsFrame.TYPE_CHAT_CHUNK)));
        verify(registry).pushToUser(eq(USER_ID), argThat(f -> typeIs(f, WsFrame.TYPE_CHAT_DONE)));
        // 聚合全文落库为 BOT 消息
        verify(chatLogService).append(eq(SESSION_ID), eq("TK-1"), eq(TicketActorType.BOT), isNull(), eq("你好"));
        // 首条消息回填标题
        verify(ticketService).fillTitle("TK-1", "你好");
    }

    @Test
    void processing_shouldForwardToAssignedAgent() {
        Ticket processing = aiServing();
        processing.requestHandoff("x");
        processing.claim("agent-9");
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(processing));
        when(keywordDetector.hit(anyString())).thenReturn(false);

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "在吗")).verifyComplete();

        verify(registry).pushToAgent(eq("agent-9"), argThat(f -> typeIs(f, WsFrame.TYPE_CHAT)));
        verify(customerServiceService, never()).chatStream(anyString(), anyString());
    }

    @Test
    void noActiveTicket_shouldCreateNewTicket() {
        Ticket created = aiServing();
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.empty());
        when(ticketService.createForSession(SESSION_ID, USER_ID, null, TicketCategory.CONSULT))
            .thenReturn(created);
        when(keywordDetector.hit(anyString())).thenReturn(false);
        when(customerServiceService.chatStream(anyString(), anyString())).thenReturn(Flux.just("hi"));

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好")).verifyComplete();

        verify(ticketService).createForSession(SESSION_ID, USER_ID, null, TicketCategory.CONSULT);
    }

    @Test
    void notOwnedSession_shouldRejectWithoutTouchingTicket() {
        StepVerifier.create(dispatch.onUserMessage(user, "uOTHER:conv-9", "hi")).verifyComplete();

        verify(registry).pushToUser(eq(USER_ID), argThat(f -> typeIs(f, WsFrame.TYPE_ERROR)));
        verifyNoInteractions(ticketService);
    }

    @Test
    void onAgentMessage_shouldPersistAndPush_offlineUserIsNotAnError() {
        Ticket processing = aiServing();
        processing.requestHandoff("x");
        processing.claim("agent-1");
        when(ticketService.find("TK-1")).thenReturn(Optional.of(processing));
        when(registry.pushToUser(eq(USER_ID), any())).thenReturn(false); // 用户离线

        StepVerifier.create(dispatch.onAgentMessage("agent-1", "TK-1", "已为您处理")).verifyComplete();

        verify(chatLogService).append(eq(SESSION_ID), eq("TK-1"), eq(TicketActorType.AGENT),
            eq("agent-1"), eq("已为您处理"));
        verify(registry).pushToUser(eq(USER_ID), argThat(f -> typeIs(f, WsFrame.TYPE_CHAT)));
    }

    @Test
    void onAgentMessage_wrongAssignee_shouldRejectAndNotPersist() {
        Ticket processing = aiServing();
        processing.requestHandoff("x");
        processing.claim("agent-1");
        when(ticketService.find("TK-1")).thenReturn(Optional.of(processing));

        StepVerifier.create(dispatch.onAgentMessage("agent-2", "TK-1", "越权回复")).verifyComplete();

        verify(registry).pushToAgent(eq("agent-2"), argThat(f -> typeIs(f, WsFrame.TYPE_ERROR)));
        verify(chatLogService, never()).append(any(), any(), any(), any(), any());
    }
}
