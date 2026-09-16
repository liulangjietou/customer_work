package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessagePhase;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatTerminal;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 直接回读权威会话存储；框架内存状态和历史缓存均不能作为持久化完成证明。 */
@Service
public class ChatCompletionVerifier {
    private static final Logger log = LoggerFactory.getLogger(ChatCompletionVerifier.class);
    /** AgentScope 2.0.3 ReAct 与 Harness delegate 共用的会话状态键。 */
    private static final String AGENT_STATE_KEY = "agent_state";
    private final AgentStateStore stateStore;

    public ChatCompletionVerifier(AgentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /** 用请求线程已捕获的主体和会话核对本轮，绝不回退到别的主体或历史分区。 */
    public ChatTerminal verify(RuntimeContext context, String turnId, Msg result) {
        if (result == null) {
            return ChatTerminal.unknown(turnId);
        }
        try {
            var saved = stateStore.get(context.getUserId(), context.getSessionId(), AGENT_STATE_KEY, AgentState.class);
            if (saved.isEmpty()) {
                return ChatTerminal.unknown(turnId);
            }
            boolean inTurn = false;
            for (Msg message : saved.get().getContext()) {
                if (message.getRole() == MsgRole.USER) {
                    inTurn = turnId.equals(message.getId());
                } else if (inTurn && message.getRole() == MsgRole.ASSISTANT
                    && Objects.equals(result.getId(), message.getId())
                    && Objects.equals(result.getTextContent(), message.getTextContent())
                    && Objects.equals(ChatMessagePhase.finishReason(result), ChatMessagePhase.finishReason(message))) {
                    var reason = ChatMessagePhase.finishReason(message);
                    var phase = ChatMessagePhase.of(message);
                    if (phase == ChatMessagePhase.PROCESS) {
                        phase = ChatMessagePhase.UNKNOWN;
                    }
                    return new ChatTerminal(turnId, message.getId(), phase,
                        reason, true, null, null);
                }
            }
        } catch (RuntimeException error) {
            log.error("Chat history confirmation failed, errorCode={}, sessionId={}",
                "CHAT_HISTORY_CONFIRM_FAILED", context.getSessionId(), error);
        }
        return ChatTerminal.unknown(turnId);
    }
}
