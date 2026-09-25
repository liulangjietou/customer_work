package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一轮对话的收尾语义：有人收尾的一轮攒下上报等组织者结算，无人收尾的一轮不攒。
 *
 * @author owlzhangfq@gmail.com
 */
class ConversationTurnTest {

    private static final String SESSION = "u1:conv-turn";
    private static final ConversationTurn.Escalation HANDOFF =
        new ConversationTurn.Escalation("IntentClassifier", null, "自动应答轮次用尽");

    @Test
    @DisplayName("无人收尾的一轮：调用仍被识别为内部调用，但上报的转人工不入待办")
    void unattendedTurnDropsEscalations() {
        ConversationTurn turn = ConversationTurn.unattended(SESSION);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("intent:" + SESSION).build();
        ctx.put(ConversationTurn.class, turn);

        assertSame(turn, ConversationTurn.delegatedBy(ctx), "调用会话不是用户会话，中间件应只上报不自行处置");
        turn.escalate(HANDOFF);

        assertFalse(turn.hasEscalations());
        assertTrue(turn.handoffReason().isEmpty());
    }

    @Test
    @DisplayName("有人收尾的一轮：上报的转人工留给组织者结算")
    void attendedTurnKeepsEscalations() {
        ConversationTurn turn = ConversationTurn.open(SESSION);

        turn.escalate(HANDOFF);

        assertTrue(turn.hasEscalations());
        assertTrue(turn.handoffReason().isPresent());
    }
}
