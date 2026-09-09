package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import com.richard.fyoung.customerwork.core.service.ChatTurnCompletion;
import com.richard.fyoung.customerwork.core.service.ChatTurnEvent;
import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.ws.InboundMessageDeduplicator;
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

import java.util.List;

/**
 * 对话分发核心单测：关键词转人工不进 LLM、AI 流式桥接、坐席转发、新会话自动建单、标题回填、坐席消息离线只落库。
 * @author owlzhangfq@gmail.com
 */
class ChatDispatchServiceTest {

    private static final String USER_ID = "U1";
    private static final String SESSION_ID = "uU1:conv-1";

    private TicketService ticketService;
    private ChatLogService chatLogService;
    private ChatTurnService chatTurnService;
    private HandoffKeywordDetector keywordDetector;
    private WsSessionRegistry registry;
    private SubjectQuotaGuard subjectQuotaGuard;
    private InboundMessageDeduplicator deduplicator;
    private ChatDispatchService dispatch;

    private final UserPrincipal user = new UserPrincipal(USER_ID, "alice", "Alice", TenantContext.DEFAULT);

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        chatLogService = mock(ChatLogService.class);
        chatTurnService = mock(ChatTurnService.class);
        keywordDetector = mock(HandoffKeywordDetector.class);
        registry = mock(WsSessionRegistry.class);
        // 默认放行：本类测的是分发路由，配额行为另有专门用例
        subjectQuotaGuard = mock(SubjectQuotaGuard.class);
        lenient().when(subjectQuotaGuard.check(any(), any())).thenReturn(SubjectQuotaDecision.allow());
        // 真实的去重器配进程内计数器：mock 掉它就等于不测去重那一段，
        // 而"重发只处理一次"恰恰是这条链路最容易在改动中丢掉的性质
        deduplicator = new InboundMessageDeduplicator(new InMemoryWindowCounter(), 300);
        dispatch = new ChatDispatchService(ticketService, chatLogService, chatTurnService,
            keywordDetector, registry, subjectQuotaGuard, deduplicator);
        // 落库统一返回一条带 messageId 的消息（AI 流式收尾需要读 messageId）
        lenient().when(chatLogService.append(any(), any(), any(), any(), any()))
            .thenReturn(ChatMessage.of("MSG-9", SESSION_ID, "TK-1", TicketActorType.BOT, null, "txt"));
    }

    private Ticket aiServing() {
        return Ticket.create("TK-1", SESSION_ID, USER_ID, null, TicketCategory.CONSULT);
    }

    private Flux<ChatTurnEvent> turn(String... chunks) {
        java.util.List<ChatTurnEvent> events = new java.util.ArrayList<>();
        StringBuilder reply = new StringBuilder();
        for (String chunk : chunks) {
            reply.append(chunk);
            events.add(new ChatTurnEvent.Delta(chunk));
        }
        ChatMessage message = ChatMessage.of("MSG-9", SESSION_ID, "TK-1",
            TicketActorType.BOT, null, reply.toString());
        ChatTerminalEnvelope terminal = new ChatTerminalEnvelope("MSG-9", "MODEL_STOP",
            new ChatUsageSnapshot(8, 2, 0, 10, 0.1), "trace-ws", List.of());
        events.add(new ChatTurnEvent.Completed(new ChatTurnCompletion(message, terminal)));
        return Flux.fromIterable(events);
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

    /**
     * 这条是本次改动的核心断言。
     *
     * <p>WebSocket 在移动网络下断连是常态，客户端重发是必须的。没有去重时，同一句话到两次
     * 就是<b>两次完整的对话轮</b>——双份 token 只是账面代价，更麻烦的是智能体会调工具：
     * 可能提交两张退款工单、发两次通知。用户看到同一个问题被回答两遍，
     * 而业务后端看到的是两次真实操作。</p>
     */
    @Test
    void duplicateClientMsgId_shouldNotTriggerSecondLlmCall() {
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(aiServing()));
        when(keywordDetector.hit(anyString())).thenReturn(false);
        when(chatTurnService.stream(SESSION_ID, "你好", "TK-1")).thenReturn(turn("你", "好"));

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好", "cmid-1")).verifyComplete();
        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好", "cmid-1")).verifyComplete();

        verify(chatTurnService, times(1)).stream(SESSION_ID, "你好", "TK-1");
        verify(chatLogService, times(1)).append(any(), any(), any(), any(), any());
    }

    /** 重发被丢弃时不该扣额度——去重排在配额判定之前正是为了这个。 */
    @Test
    void duplicateDelivery_shouldNotConsumeQuota() {
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(aiServing()));
        when(keywordDetector.hit(anyString())).thenReturn(false);
        when(chatTurnService.stream(SESSION_ID, "你好", "TK-1")).thenReturn(turn("你", "好"));

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好", "cmid-2")).verifyComplete();
        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好", "cmid-2")).verifyComplete();

        verify(subjectQuotaGuard, times(1)).recordRequest(any());
    }

    /** 两条不同的消息各走各的，去重不能把正常对话吞掉。 */
    @Test
    void distinctClientMsgIds_bothProcessed() {
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(aiServing()));
        when(keywordDetector.hit(anyString())).thenReturn(false);
        when(chatTurnService.stream(eq(SESSION_ID), anyString(), eq("TK-1"))).thenReturn(turn("回", "复"));

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "第一句", "cmid-a")).verifyComplete();
        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "第二句", "cmid-b")).verifyComplete();

        verify(chatTurnService).stream(SESSION_ID, "第一句", "TK-1");
        verify(chatTurnService).stream(SESSION_ID, "第二句", "TK-1");
    }

    /** 老客户端不带标识：一律按首次处理，不能因为服务端升级就把它们的消息吞了。 */
    @Test
    void missingClientMsgId_alwaysProcessed() {
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(aiServing()));
        when(keywordDetector.hit(anyString())).thenReturn(false);
        when(chatTurnService.stream(SESSION_ID, "你好", "TK-1")).thenReturn(turn("你", "好"));

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好")).verifyComplete();
        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好")).verifyComplete();

        verify(chatTurnService, times(2)).stream(SESSION_ID, "你好", "TK-1");
    }

    @Test
    void keywordHit_shouldRequestHandoff_andNotCallLlm() {
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(aiServing()));
        when(keywordDetector.hit("我要转人工")).thenReturn(true);

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "我要转人工")).verifyComplete();

        verify(ticketService).requestHandoff(eq(SESSION_ID), anyString(), eq(TicketActorType.USER), eq(USER_ID));
        verify(chatTurnService, never()).stream(anyString(), anyString(), any());
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
        when(chatTurnService.stream(SESSION_ID, "你好", "TK-1")).thenReturn(turn("你", "好"));

        StepVerifier.create(dispatch.onUserMessage(user, SESSION_ID, "你好")).verifyComplete();

        verify(registry, times(2)).pushToUser(eq(USER_ID), argThat(f -> typeIs(f, WsFrame.TYPE_CHAT_CHUNK)));
        verify(registry).pushToUser(eq(USER_ID), argThat(f -> typeIs(f, WsFrame.TYPE_CHAT_DONE)));
        verify(registry).pushToUser(eq(USER_ID), argThat(frame -> {
            if (!typeIs(frame, WsFrame.TYPE_CHAT_DONE)) {
                return false;
            }
            java.util.Map<?, ?> data = (java.util.Map<?, ?>) ((WsFrame) frame).data();
            return "MSG-9".equals(data.get("messageId"))
                && "MODEL_STOP".equals(data.get("finishReason"))
                && "trace-ws".equals(data.get("traceId"));
        }));
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
        verify(chatTurnService, never()).stream(anyString(), anyString(), any());
    }

    @Test
    void noActiveTicket_shouldCreateNewTicket() {
        Ticket created = aiServing();
        when(ticketService.findActiveBySession(SESSION_ID)).thenReturn(Optional.empty());
        when(ticketService.createForSession(SESSION_ID, USER_ID, null, TicketCategory.CONSULT))
            .thenReturn(created);
        when(keywordDetector.hit(anyString())).thenReturn(false);
        when(chatTurnService.stream(anyString(), anyString(), anyString())).thenReturn(turn("hi"));

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
