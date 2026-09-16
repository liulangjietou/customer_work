package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessagePhase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查存储证据、消息轮次和结束原因，避免内存中的结果掩盖保存失败。 */
class ChatCompletionVerifierTest {
    private static final String USER_ID = "tenant-a:agent:subject-1";
    private static final String SESSION_ID = "session-1";
    private final AgentStateStore store = mock(AgentStateStore.class);
    private final ChatCompletionVerifier verifier = new ChatCompletionVerifier(store);
    private final RuntimeContext context = RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
    private final Msg input = Msg.builder().id("turn-1").role(MsgRole.USER).textContent("处理请求").build();

    @ParameterizedTest
    @EnumSource(GenerateReason.class)
    void verify_shouldUsePersistedReason(GenerateReason reason) {
        Msg result = result(reason);
        saved(input, result);
        var terminal = verifier.verify(context, input.getId(), result);
        var expected = ChatMessagePhase.of(result);
        assertEquals(expected == ChatMessagePhase.PROCESS ? ChatMessagePhase.UNKNOWN : expected, terminal.phase());
        assertTrue(terminal.historySaved());
        assertEquals(result.getId(), terminal.messageId());
        assertEquals(reason.name(), terminal.finishReason());
        verify(store).get(USER_ID, SESSION_ID, "agent_state", AgentState.class);
        verifyNoMoreInteractions(store);
    }

    @Test
    void verify_shouldRejectResultFromAnotherTurn() {
        Msg result = result(GenerateReason.MODEL_STOP);
        saved(input, Msg.builder().role(MsgRole.USER).textContent("另一个请求").build(), result);
        assertUnknown(result);
    }

    @Test
    void verify_shouldNotTreatOldSnapshotAsCurrentReply() {
        Msg result = result(GenerateReason.MODEL_STOP);
        saved(input, Msg.builder().id("other-reply").role(MsgRole.ASSISTANT)
            .textContent("已处理").generateReason(GenerateReason.MODEL_STOP).build());
        assertUnknown(result);
    }

    @Test
    void verify_shouldRejectMissingOrChangedPersistedReason() {
        Msg result = result(GenerateReason.MODEL_STOP);
        saved(input, Msg.builder().id(result.getId()).role(MsgRole.ASSISTANT).textContent(result.getTextContent()).build());
        assertUnknown(result);
    }

    @Test
    void verify_shouldReturnUnknownWhenStorageFails() {
        when(store.get(USER_ID, SESSION_ID, "agent_state", AgentState.class))
            .thenThrow(new IllegalStateException("private database address"));
        var terminal = verifier.verify(context, input.getId(), result(GenerateReason.INTERRUPTED));
        assertEquals(ChatMessagePhase.UNKNOWN, terminal.phase());
        assertFalse(terminal.historySaved());
        assertFalse(terminal.error().contains("private database address"));
    }

    @Test
    void verify_shouldNotGuessFinalWhenNoRootResultExists() {
        assertEquals(ChatMessagePhase.UNKNOWN, verifier.verify(context, input.getId(), null).phase());
        verifyNoInteractions(store);
    }

    private void assertUnknown(Msg result) {
        var terminal = verifier.verify(context, input.getId(), result);
        assertEquals(ChatMessagePhase.UNKNOWN, terminal.phase());
        assertFalse(terminal.historySaved());
    }

    private Msg result(GenerateReason reason) {
        return Msg.builder().id("reply-1").role(MsgRole.ASSISTANT).textContent("已处理")
            .generateReason(reason).build();
    }

    private void saved(Msg... messages) {
        AgentState state = AgentState.builder().userId(USER_ID).sessionId(SESSION_ID).context(List.of(messages)).build();
        // JSON 往返使本测试读取存储形态，避免仅靠同一个内存对象自证。
        when(store.get(USER_ID, SESSION_ID, "agent_state", AgentState.class))
            .thenReturn(Optional.of(AgentState.fromJsonString(state.toJson())));
    }
}
