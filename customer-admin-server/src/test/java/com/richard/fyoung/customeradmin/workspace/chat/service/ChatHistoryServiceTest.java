package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageVO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatSessionSummary;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.ChatSessionStateQueryMapper;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentStateAccessor;
import com.richard.fyoung.customerwork.data.attachment.AttachmentParseStatus;
import com.richard.fyoung.customerwork.data.attachment.AttachmentStore;
import com.richard.fyoung.customerwork.data.attachment.ChatAttachment;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatHistoryService#listSessions(String, long, long)} 单测：验证 SQL 分页契约——
 * ① 顺序以 mapper 返回的 updated_at 倒序为准、去掉 {@code {agentCode}:} 前缀后逐个组装；
 * ② offset 按 {@code (page-1)*size} 计算；
 * ③ context 为空的会话跳过（本页可能略少于 size）；
 * ④ 总数为 0 时短路、不再查页数据。
 * @author owlzhangfq@gmail.com
 */
class ChatHistoryServiceTest {

    private static final String AGENT_CODE = "coder";

    private AgentInstanceCache agentInstanceCache;
    private AgentStateStore agentStateStore;
    private AgentStateAccessor agentStateAccessor;
    private ChatHistoryCache historyCache;
    private ChatSessionStateQueryMapper sessionStateQueryMapper;
    private AttachmentStore attachmentStore;
    private ChatHistoryService service;
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        agentInstanceCache = mock(AgentInstanceCache.class);
        agentStateStore = mock(AgentStateStore.class);
        agentStateAccessor = mock(AgentStateAccessor.class);
        historyCache = mock(ChatHistoryCache.class);
        sessionStateQueryMapper = mock(ChatSessionStateQueryMapper.class);
        attachmentStore = mock(AttachmentStore.class);
        service = new ChatHistoryService(agentInstanceCache, agentStateStore, agentStateAccessor,
            historyCache, sessionStateQueryMapper, attachmentStore);

        agent = mock(ReActAgent.class);
        when(agentInstanceCache.getOrBuild(AGENT_CODE)).thenReturn(agent);
    }

    /** 给某个裸 sessionId 备好一段上下文（首条为用户消息，供 preview 取值）。 */
    private void stubContext(String sessionId, Msg... msgs) {
        AgentState state = mock(AgentState.class);
        when(state.getContext()).thenReturn(List.of(msgs));
        when(agentStateAccessor.resolve(agent, AGENT_CODE, sessionId)).thenReturn(state);
    }

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private Msg assistantMsg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    @Test
    void listSessions_shouldPageInSqlOrder_stripPrefix_andBuildSummaries() {
        when(sessionStateQueryMapper.countSessions(AGENT_CODE)).thenReturn(5L);
        when(sessionStateQueryMapper.pageSessionIds(eq(AGENT_CODE), eq(0L), eq(20L)))
            .thenReturn(List.of("coder:s1", "coder:s2"));
        stubContext("s1", userMsg("你好"), assistantMsg("在的"));
        stubContext("s2", userMsg("在吗"));

        PageResult<ChatSessionSummary> result = service.listSessions(AGENT_CODE, 1, 20);

        assertEquals(1, result.getPageNum());
        assertEquals(20, result.getPageSize());
        assertEquals(5, result.getTotal());
        assertEquals(2, result.getList().size());
        // 顺序以 SQL 返回为准，sessionId 已去前缀
        assertEquals("s1", result.getList().get(0).sessionId());
        assertEquals("你好", result.getList().get(0).preview());
        assertEquals(2, result.getList().get(0).messageCount());
        assertEquals("s2", result.getList().get(1).sessionId());
        assertEquals(1, result.getList().get(1).messageCount());
    }

    @Test
    void listSessions_shouldComputeOffset_fromPageAndSize() {
        when(sessionStateQueryMapper.countSessions(AGENT_CODE)).thenReturn(50L);
        when(sessionStateQueryMapper.pageSessionIds(eq(AGENT_CODE), eq(20L), eq(20L)))
            .thenReturn(List.of("coder:s3"));
        stubContext("s3", userMsg("第三页第一条"));

        PageResult<ChatSessionSummary> result = service.listSessions(AGENT_CODE, 2, 20);

        // offset = (2-1)*20 = 20
        verify(sessionStateQueryMapper).pageSessionIds(eq(AGENT_CODE), eq(20L), eq(20L));
        assertEquals(1, result.getList().size());
        assertEquals("s3", result.getList().get(0).sessionId());
    }

    @Test
    void listSessions_shouldSkipEmptyContextSessions_soPageMayBeShorterThanSize() {
        when(sessionStateQueryMapper.countSessions(AGENT_CODE)).thenReturn(2L);
        when(sessionStateQueryMapper.pageSessionIds(eq(AGENT_CODE), eq(0L), eq(20L)))
            .thenReturn(List.of("coder:empty", "coder:s2"));
        stubContext("empty"); // 空上下文
        stubContext("s2", userMsg("在吗"));

        PageResult<ChatSessionSummary> result = service.listSessions(AGENT_CODE, 1, 20);

        // 空会话被跳过，本页只剩 1 条，但 total 仍是去重会话总数 2
        assertEquals(2, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("s2", result.getList().get(0).sessionId());
    }

    @Test
    void getMessages_shouldAttachAttachments_groupedByMessageId() {
        // 缓存未命中 → 回源 state；附件按 message_id 挂回对应用户消息，无附件的消息给空列表（契约要求非 null）。
        String sessionId = "sess-1";
        when(historyCache.getMessages(AGENT_CODE, sessionId)).thenReturn(Optional.empty());

        Msg userMsg = Msg.builder().id("m1").role(MsgRole.USER).textContent("看下这个附件").build();
        Msg assistantMsg = Msg.builder().id("m2").role(MsgRole.ASSISTANT).textContent("好的").build();
        AgentState state = mock(AgentState.class);
        when(state.getContext()).thenReturn(List.of(userMsg, assistantMsg));
        when(agentStateAccessor.resolve(agent, AGENT_CODE, sessionId)).thenReturn(state);

        ChatAttachment attachment = ChatAttachment.builder()
            .id("att-1").messageId("m1").fileName("spec.pdf").mimeType("application/pdf")
            .fileSize(2048L).parseStatus(AttachmentParseStatus.SUCCESS).build();
        when(attachmentStore.listBySession(sessionId)).thenReturn(List.of(attachment));

        List<ChatMessageVO> messages = service.getMessages(AGENT_CODE, sessionId);

        assertEquals(2, messages.size());
        // 用户消息带附件（id=框架 Msg.id）
        assertEquals("m1", messages.get(0).id());
        assertEquals(1, messages.get(0).attachments().size());
        assertEquals("att-1", messages.get(0).attachments().get(0).id());
        assertEquals("spec.pdf", messages.get(0).attachments().get(0).fileName());
        assertEquals("SUCCESS", messages.get(0).attachments().get(0).parseStatus());
        assertEquals(2048L, messages.get(0).attachments().get(0).fileSize());
        // 助手消息无附件 → 空列表而非 null
        assertEquals("m2", messages.get(1).id());
        assertTrue(messages.get(1).attachments().isEmpty());
    }

    @Test
    void getMessages_shouldReturnEmptyAttachments_whenAttachmentUnbound() {
        // message_id 为空（仅上传未发送）的附件不应挂到任何消息上。
        String sessionId = "sess-2";
        when(historyCache.getMessages(AGENT_CODE, sessionId)).thenReturn(Optional.empty());
        Msg userMsg = Msg.builder().id("m1").role(MsgRole.USER).textContent("hi").build();
        AgentState state = mock(AgentState.class);
        when(state.getContext()).thenReturn(List.of(userMsg));
        when(agentStateAccessor.resolve(agent, AGENT_CODE, sessionId)).thenReturn(state);
        ChatAttachment unbound = ChatAttachment.builder()
            .id("att-x").messageId("").fileName("x.txt").mimeType("text/plain")
            .fileSize(1L).parseStatus(AttachmentParseStatus.SUCCESS).build();
        when(attachmentStore.listBySession(sessionId)).thenReturn(List.of(unbound));

        List<ChatMessageVO> messages = service.getMessages(AGENT_CODE, sessionId);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).attachments().isEmpty());
    }

    @Test
    void listSessions_shouldShortCircuit_whenTotalIsZero() {
        when(sessionStateQueryMapper.countSessions(AGENT_CODE)).thenReturn(0L);

        PageResult<ChatSessionSummary> result = service.listSessions(AGENT_CODE, 1, 20);

        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
        // 总数为 0 时不再查页数据
        verify(sessionStateQueryMapper, never()).pageSessionIds(eq(AGENT_CODE), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong());
    }
}
