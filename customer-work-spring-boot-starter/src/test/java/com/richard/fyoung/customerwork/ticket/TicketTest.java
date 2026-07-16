package com.richard.fyoung.customerwork.ticket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单充血实体状态机单测：逐条覆盖合法流转（状态/时间戳/幂等返回值）与非法流转 fast-fail。
 * @author owlzhangfq@gmail.com
 */
class TicketTest {

    private static Ticket aiServing() {
        return Ticket.create("TK-1", "s1", "u1", "标题", TicketCategory.CONSULT);
    }

    private static Ticket waitingAgent() {
        Ticket t = aiServing();
        t.requestHandoff("投诉升级");
        return t;
    }

    private static Ticket processing() {
        Ticket t = waitingAgent();
        t.claim("agent-1");
        return t;
    }

    private static Ticket onHold() {
        Ticket t = processing();
        t.hold();
        return t;
    }

    private static Ticket waitingConfirm() {
        Ticket t = processing();
        t.markResolved("已处理");
        return t;
    }

    private static Ticket resolved() {
        Ticket t = waitingConfirm();
        t.confirm();
        return t;
    }

    @Test
    void create_shouldStartAtAiServing() {
        Ticket t = aiServing();
        assertEquals(TicketStatus.AI_SERVING, t.getStatus());
        assertEquals(TicketPriority.NORMAL, t.getPriority());
        assertEquals(TicketCategory.CONSULT, t.getCategory());
        assertTrue(t.getCreatedAtMs() > 0);
        assertEquals(t.getCreatedAtMs(), t.getUpdatedAtMs());
    }

    @Test
    void create_nullCategory_shouldFallbackToOther() {
        Ticket t = Ticket.create("TK-x", "s", "u", "t", null);
        assertEquals(TicketCategory.OTHER, t.getCategory());
    }

    @Test
    void requestHandoff_fromAiServing_shouldFlowAndReturnTrue() {
        Ticket t = aiServing();
        assertTrue(t.requestHandoff("要人工"));
        assertEquals(TicketStatus.WAITING_AGENT, t.getStatus());
        assertTrue(t.getHandoffAtMs() > 0);
        assertEquals("要人工", t.getHandoffReason());
    }

    @Test
    void requestHandoff_whenAlreadyInHumanChain_shouldBeIdempotentFalse() {
        assertFalse(waitingAgent().requestHandoff("再次"));
        assertFalse(processing().requestHandoff("再次"));
        assertFalse(onHold().requestHandoff("再次"));
    }

    @Test
    void requestHandoff_fromTerminalOrConfirm_shouldFastFail() {
        assertThrows(IllegalStateException.class, () -> waitingConfirm().requestHandoff("x"));
        assertThrows(IllegalStateException.class, () -> resolved().requestHandoff("x"));
        Ticket closed = aiServing();
        closed.close("done");
        assertThrows(IllegalStateException.class, () -> closed.requestHandoff("x"));
    }

    @Test
    void cancelHandoff_shouldReturnToAiServing() {
        Ticket t = waitingAgent();
        t.cancelHandoff();
        assertEquals(TicketStatus.AI_SERVING, t.getStatus());
        assertThrows(IllegalStateException.class, () -> aiServing().cancelHandoff());
    }

    @Test
    void claim_shouldBindAgentAndTimestamp() {
        Ticket t = waitingAgent();
        t.claim("agent-9");
        assertEquals(TicketStatus.PROCESSING, t.getStatus());
        assertEquals("agent-9", t.getAssignee());
        assertTrue(t.getClaimedAtMs() > 0);
        assertThrows(IllegalStateException.class, () -> aiServing().claim("a"));
        assertThrows(IllegalStateException.class, () -> processing().claim("a"));
    }

    @Test
    void holdAndResume_shouldToggleBetweenProcessingAndOnHold() {
        Ticket t = processing();
        t.hold();
        assertEquals(TicketStatus.ON_HOLD, t.getStatus());
        t.resume();
        assertEquals(TicketStatus.PROCESSING, t.getStatus());
        assertThrows(IllegalStateException.class, () -> waitingAgent().hold());
        assertThrows(IllegalStateException.class, () -> processing().resume());
    }

    @Test
    void transferToPool_shouldClearAssigneeAndRequeue() {
        Ticket fromProcessing = processing();
        fromProcessing.transferToPool();
        assertEquals(TicketStatus.WAITING_AGENT, fromProcessing.getStatus());
        assertNull(fromProcessing.getAssignee());
        assertTrue(fromProcessing.getHandoffAtMs() > 0);

        Ticket fromHold = onHold();
        fromHold.transferToPool();
        assertEquals(TicketStatus.WAITING_AGENT, fromHold.getStatus());

        assertThrows(IllegalStateException.class, () -> aiServing().transferToPool());
    }

    @Test
    void transferToAgent_shouldSwapAssigneeKeepStatus() {
        Ticket t = processing();
        t.transferToAgent("agent-2");
        assertEquals(TicketStatus.PROCESSING, t.getStatus());
        assertEquals("agent-2", t.getAssignee());
        assertThrows(IllegalStateException.class, () -> onHold().transferToAgent("a"));
    }

    @Test
    void markResolved_shouldMoveToWaitingConfirm() {
        Ticket t = processing();
        t.markResolved("修好了");
        assertEquals(TicketStatus.WAITING_CONFIRM, t.getStatus());
        assertEquals("修好了", t.getResolveNote());
        assertThrows(IllegalStateException.class, () -> onHold().markResolved("x"));
    }

    @Test
    void confirm_shouldResolveWithTimestamp() {
        Ticket t = waitingConfirm();
        t.confirm();
        assertEquals(TicketStatus.RESOLVED, t.getStatus());
        assertTrue(t.getResolvedAtMs() > 0);
        assertThrows(IllegalStateException.class, () -> processing().confirm());
    }

    @Test
    void reject_shouldBounceBackToProcessing() {
        Ticket t = waitingConfirm();
        t.reject("没解决");
        assertEquals(TicketStatus.PROCESSING, t.getStatus());
        assertEquals("没解决", t.getResolveNote());
        assertThrows(IllegalStateException.class, () -> processing().reject("x"));
    }

    @Test
    void close_shouldAllowFromAiServingResolvedWaitingConfirm() {
        Ticket a = aiServing();
        a.close("用户离开");
        assertEquals(TicketStatus.CLOSED, a.getStatus());
        assertTrue(a.getClosedAtMs() > 0);

        Ticket r = resolved();
        r.close("归档");
        assertEquals(TicketStatus.CLOSED, r.getStatus());

        Ticket w = waitingConfirm();
        w.close("提前关");
        assertEquals(TicketStatus.CLOSED, w.getStatus());

        assertThrows(IllegalStateException.class, () -> processing().close("x"));
        assertThrows(IllegalStateException.class, () -> onHold().close("x"));
    }

    @Test
    void forceClose_shouldAllowFromAnyNonClosedState() {
        // 覆盖普通 close 不放行的人工链路态（WAITING_AGENT/PROCESSING/ON_HOLD）
        Ticket wa = waitingAgent();
        wa.forceClose("idle timeout");
        assertEquals(TicketStatus.CLOSED, wa.getStatus());
        assertTrue(wa.getClosedAtMs() > 0);
        assertEquals("idle timeout", wa.getResolveNote());

        Ticket p = processing();
        p.forceClose("idle timeout");
        assertEquals(TicketStatus.CLOSED, p.getStatus());

        Ticket h = onHold();
        h.forceClose("idle timeout");
        assertEquals(TicketStatus.CLOSED, h.getStatus());

        // 以及普通 close 本就放行的态
        Ticket ai = aiServing();
        ai.forceClose("user force close");
        assertEquals(TicketStatus.CLOSED, ai.getStatus());

        Ticket wc = waitingConfirm();
        wc.forceClose("idle timeout");
        assertEquals(TicketStatus.CLOSED, wc.getStatus());
    }

    @Test
    void forceClose_whenAlreadyClosed_shouldFastFail() {
        Ticket closed = aiServing();
        closed.close("done");
        assertThrows(IllegalStateException.class, () -> closed.forceClose("再关"));
    }

    @Test
    void markUserActive_shouldRefreshLastActiveTimestamp() {
        Ticket t = aiServing();
        long before = t.getLastUserActiveAtMs();
        assertTrue(before > 0, "建单即视为一次活跃");
        t.markUserActive();
        assertTrue(t.getLastUserActiveAtMs() >= before, "markUserActive 应刷新最后活跃时间");
        // 用户驱动流转应联动刷新最后活跃时间
        Ticket handoff = aiServing();
        long h0 = handoff.getLastUserActiveAtMs();
        handoff.requestHandoff("要人工");
        assertTrue(handoff.getLastUserActiveAtMs() >= h0);
    }

    @Test
    void reopen_shouldRequeueAndCountUp() {
        Ticket r = resolved();
        r.reopen("又出问题");
        assertEquals(TicketStatus.WAITING_AGENT, r.getStatus());
        assertEquals(1, r.getReopenCount());
        assertNull(r.getAssignee());
        assertTrue(r.getHandoffAtMs() > 0);

        Ticket closed = aiServing();
        closed.close("done");
        closed.reopen("重开");
        assertEquals(TicketStatus.WAITING_AGENT, closed.getStatus());
        assertEquals(1, closed.getReopenCount());

        assertThrows(IllegalStateException.class, () -> processing().reopen("x"));
    }

    @Test
    void reopenToAi_shouldReturnToAiServingAndCountUp() {
        // RESOLVED → AI_SERVING：回 AI 自助而非人工排队，reopen 计数 +1、清坐席、刷新用户活跃基准
        Ticket r = resolved();
        long before = r.getLastUserActiveAtMs();
        r.reopenToAi("重新开始对话");
        assertEquals(TicketStatus.AI_SERVING, r.getStatus());
        assertEquals(1, r.getReopenCount());
        assertNull(r.getAssignee());
        assertTrue(r.getLastUserActiveAtMs() >= before);

        // CLOSED → AI_SERVING 同样允许
        Ticket closed = aiServing();
        closed.close("done");
        closed.reopenToAi("重开");
        assertEquals(TicketStatus.AI_SERVING, closed.getStatus());

        // 非 RESOLVED|CLOSED 态 fast-fail
        assertThrows(IllegalStateException.class, () -> processing().reopenToAi("x"));
    }

    @Test
    void fillTitleIfBlank_shouldFillOnlyWhenBlank() {
        // 已有标题：不覆盖
        Ticket withTitle = Ticket.create("TK-a", "s", "u", "原标题", TicketCategory.CONSULT);
        assertFalse(withTitle.fillTitleIfBlank("新标题"));
        assertEquals("原标题", withTitle.getTitle());

        // 空白标题：回填并刷新时间戳
        Ticket blank = Ticket.create("TK-b", "s", "u", "  ", TicketCategory.CONSULT);
        long before = blank.getUpdatedAtMs();
        assertTrue(blank.fillTitleIfBlank("回填标题"));
        assertEquals("回填标题", blank.getTitle());
        assertTrue(blank.getUpdatedAtMs() >= before);

        // null 标题：同样视为空白可回填
        Ticket nullTitle = Ticket.create("TK-c", "s", "u", null, TicketCategory.CONSULT);
        assertTrue(nullTitle.fillTitleIfBlank("补白"));
        assertEquals("补白", nullTitle.getTitle());

        // 给定标题为空白：不回填
        Ticket stillBlank = Ticket.create("TK-d", "s", "u", null, TicketCategory.CONSULT);
        assertFalse(stillBlank.fillTitleIfBlank("   "));
        assertNull(stillBlank.getTitle());

        // 不限状态：CLOSED 也可回填
        Ticket closed = Ticket.create("TK-e", "s", "u", null, TicketCategory.CONSULT);
        closed.close("done");
        assertTrue(closed.fillTitleIfBlank("关单后回填"));
        assertEquals("关单后回填", closed.getTitle());
    }

    @Test
    void changePriorityAndCategory_shouldWorkWhenNotClosed_andFailWhenClosed() {
        Ticket t = aiServing();
        t.changePriority(TicketPriority.URGENT);
        assertEquals(TicketPriority.URGENT, t.getPriority());
        t.changeCategory(TicketCategory.COMPLAINT);
        assertEquals(TicketCategory.COMPLAINT, t.getCategory());

        Ticket closed = aiServing();
        closed.close("done");
        assertThrows(IllegalStateException.class, () -> closed.changePriority(TicketPriority.LOW));
        assertThrows(IllegalStateException.class, () -> closed.changeCategory(TicketCategory.OTHER));
    }
}
