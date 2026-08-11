package com.richard.fyoung.customerwork.data.ticket;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单服务单测（内存存储）：建单幂等、转人工定位与幂等、抢单 409 语义、全流转事件序列、监听器广播与容错。
 * @author owlzhangfq@gmail.com
 */
class TicketServiceTest {

    /** 记录收到的所有事件的监听器。 */
    static class RecordingListener implements TicketEventListener {
        final List<TicketEvent> received = new ArrayList<>();
        @Override
        public void onTicketEvent(Ticket ticket, TicketEvent event) {
            received.add(event);
        }
    }

    /** 总是抛异常的监听器（验证不影响主流程与其他监听器）。 */
    static class ThrowingListener implements TicketEventListener {
        @Override
        public void onTicketEvent(Ticket ticket, TicketEvent event) {
            throw new RuntimeException("listener boom");
        }
    }

    private TicketService service(TicketEventListener... listeners) {
        return new TicketService(new InMemoryTicketStore(), TicketTestSupport.providerOf(listeners));
    }

    @Test
    void createForSession_shouldBeIdempotentPerActiveSession() {
        TicketService svc = service();
        Ticket first = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        Ticket second = svc.createForSession("s1", "u1", "另一个标题", TicketCategory.CONSULT);
        assertEquals(first.getId(), second.getId(), "同会话活跃工单应幂等返回同一张");
    }

    @Test
    void requestHandoff_withoutActiveTicket_shouldFastFail() {
        TicketService svc = service();
        assertThrows(IllegalStateException.class,
            () -> svc.requestHandoff("no-such-session", "要人工", TicketActorType.USER, "u1"));
    }

    @Test
    void requestHandoff_shouldFlowOnceAndNotDuplicateEvents() {
        RecordingListener listener = new RecordingListener();
        TicketService svc = service(listener);
        svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);

        Ticket first = svc.requestHandoff("s1", "投诉", TicketActorType.USER, "u1");
        assertEquals(TicketStatus.WAITING_AGENT, first.getStatus());

        // 再次请求转人工：已在人工链路，幂等空转，不再发事件
        Ticket second = svc.requestHandoff("s1", "又一次", TicketActorType.USER, "u1");
        assertEquals(TicketStatus.WAITING_AGENT, second.getStatus());

        long handoffEvents = svc.findEvents(first.getId()).stream()
            .filter(e -> e.eventType() == TicketEventType.REQUEST_HANDOFF).count();
        assertEquals(1, handoffEvents, "重复转人工不应重复发 REQUEST_HANDOFF 事件");
    }

    @Test
    void claim_shouldSucceedThenRejectSecondClaim() {
        TicketService svc = service();
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        svc.requestHandoff("s1", "要人工", TicketActorType.USER, "u1");

        Ticket claimed = svc.claim(t.getId(), "agent-1");
        assertEquals(TicketStatus.PROCESSING, claimed.getStatus());
        assertEquals("agent-1", claimed.getAssignee());

        // 已被抢：第二次抢单 fast-fail（409 语义）
        assertThrows(IllegalStateException.class, () -> svc.claim(t.getId(), "agent-2"));
    }

    @Test
    void claimAtomically_secondCallShouldReturnFalse() {
        InMemoryTicketStore store = new InMemoryTicketStore();
        TicketService svc = new TicketService(store, TicketTestSupport.providerOf());
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        svc.requestHandoff("s1", "要人工", TicketActorType.USER, "u1");

        assertTrue(store.claimAtomically(t.getId(), "agent-1", System.currentTimeMillis()));
        assertFalse(store.claimAtomically(t.getId(), "agent-2", System.currentTimeMillis()));
    }

    @Test
    void fullLifecycle_shouldAppendEventsInOrder() {
        TicketService svc = service();
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.AFTER_SALE);
        svc.requestHandoff("s1", "要人工", TicketActorType.USER, "u1");
        svc.claim(t.getId(), "agent-1");
        svc.hold(t.getId(), TicketActorType.AGENT, "agent-1");
        svc.resume(t.getId(), TicketActorType.AGENT, "agent-1");
        svc.markResolved(t.getId(), "已处理", TicketActorType.AGENT, "agent-1");
        svc.confirm(t.getId(), TicketActorType.USER, "u1");

        List<TicketEventType> types = svc.findEvents(t.getId()).stream()
            .map(TicketEvent::eventType).toList();
        assertEquals(List.of(
            TicketEventType.CREATE,
            TicketEventType.REQUEST_HANDOFF,
            TicketEventType.CLAIM,
            TicketEventType.HOLD,
            TicketEventType.RESUME,
            TicketEventType.MARK_RESOLVED,
            TicketEventType.CONFIRM), types);
    }

    @Test
    void listener_shouldReceiveBroadcastForEachTransition() {
        RecordingListener listener = new RecordingListener();
        TicketService svc = service(listener);
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        svc.requestHandoff("s1", "要人工", TicketActorType.USER, "u1");
        svc.claim(t.getId(), "agent-1");

        assertEquals(3, listener.received.size(), "CREATE/REQUEST_HANDOFF/CLAIM 各广播一次");
        assertEquals(TicketEventType.CREATE, listener.received.get(0).eventType());
        assertEquals(TicketEventType.CLAIM, listener.received.get(2).eventType());
    }

    @Test
    void listenerThrowing_shouldNotBreakMainFlowOrOtherListeners() {
        RecordingListener good = new RecordingListener();
        TicketService svc = service(new ThrowingListener(), good);

        // 主流程不应因监听器异常中断
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        assertEquals(TicketStatus.AI_SERVING, t.getStatus());
        // 正常监听器仍应收到事件
        assertFalse(good.received.isEmpty());
    }

    @Test
    void fillTitle_shouldFillBlankTitleWithoutEmittingEvent() {
        RecordingListener listener = new RecordingListener();
        TicketService svc = service(listener);
        // 建单时标题留空
        Ticket t = svc.createForSession("s1", "u1", null, TicketCategory.CONSULT);
        int eventsBefore = listener.received.size();

        boolean filled = svc.fillTitle(t.getId(), "我的耳机连不上蓝牙");
        assertTrue(filled);
        assertEquals("我的耳机连不上蓝牙", svc.find(t.getId()).orElseThrow().getTitle());
        // 标题回填不应发事件（非状态流转）
        assertEquals(eventsBefore, listener.received.size(), "标题回填不应广播事件");

        // 已有标题：二次回填无效
        assertFalse(svc.fillTitle(t.getId(), "另一个标题"));
        assertEquals("我的耳机连不上蓝牙", svc.find(t.getId()).orElseThrow().getTitle());
    }

    @Test
    void forceClose_shouldCloseFromHumanChainAndEmitForceCloseEvent() {
        RecordingListener listener = new RecordingListener();
        TicketService svc = service(listener);
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        svc.requestHandoff("s1", "要人工", TicketActorType.USER, "u1");
        svc.claim(t.getId(), "agent-1"); // PROCESSING —— 普通 close 不放行

        Ticket closed = svc.forceClose(t.getId(), "idle timeout", TicketActorType.SYSTEM, "sla-scheduler");
        assertEquals(TicketStatus.CLOSED, closed.getStatus());

        assertTrue(svc.findEvents(t.getId()).stream()
                .anyMatch(e -> e.eventType() == TicketEventType.FORCE_CLOSE),
            "强制关闭应追加 FORCE_CLOSE 事件");
        assertTrue(listener.received.stream()
                .anyMatch(e -> e.eventType() == TicketEventType.FORCE_CLOSE),
            "强制关闭应广播给监听器（供 WS 推关闭事件）");
    }

    @Test
    void touchUserActive_shouldRefreshOnlyWhenActiveTicketExists() {
        RecordingListener listener = new RecordingListener();
        TicketService svc = service(listener);
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        long before = t.getLastUserActiveAtMs();
        int eventsBefore = listener.received.size();

        assertTrue(svc.touchUserActive("s1"), "会话有活跃工单应刷新成功");
        assertTrue(svc.find(t.getId()).orElseThrow().getLastUserActiveAtMs() >= before);
        // 刷新活跃时间不是状态流转，不应发事件
        assertEquals(eventsBefore, listener.received.size(), "刷新活跃时间不应广播事件");

        assertFalse(svc.touchUserActive("no-such-session"), "无活跃工单会话应返回 false");
    }

    @Test
    void findActiveByUser_shouldReturnLatestNonTerminalAndSkipClosed() {
        TicketService svc = service();
        svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        assertTrue(svc.findActiveByUser("u1").isPresent(), "存在进行中会话应命中");
        assertTrue(svc.findActiveByUser("other").isEmpty(), "他人无活跃会话应为空");

        // 关闭后不再算活跃
        Ticket t = svc.findActiveByUser("u1").orElseThrow();
        svc.forceClose(t.getId(), "done", TicketActorType.USER, "u1");
        assertTrue(svc.findActiveByUser("u1").isEmpty(), "已关闭后用户不应再有活跃会话");
    }

    @Test
    void reopen_shouldRequeueAndIncrementCount() {
        TicketService svc = service();
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        svc.requestHandoff("s1", "要人工", TicketActorType.USER, "u1");
        svc.claim(t.getId(), "agent-1");
        svc.markResolved(t.getId(), "已处理", TicketActorType.AGENT, "agent-1");
        svc.confirm(t.getId(), TicketActorType.USER, "u1");

        Ticket reopened = svc.reopen(t.getId(), "又出问题", TicketActorType.USER, "u1");
        assertEquals(TicketStatus.WAITING_AGENT, reopened.getStatus());
        assertEquals(1, reopened.getReopenCount());
    }

    @Test
    void reopenToAi_shouldReturnToAiServing() {
        // 用户端"重新开始对话"：重开回 AI 自助，不进人工排队，重开后该会话重新成为用户的活跃会话
        TicketService svc = service();
        Ticket t = svc.createForSession("s1", "u1", "标题", TicketCategory.ORDER);
        svc.close(t.getId(), "done", TicketActorType.USER, "u1");

        Ticket reopened = svc.reopenToAi(t.getId(), "重新开始对话", TicketActorType.USER, "u1");
        assertEquals(TicketStatus.AI_SERVING, reopened.getStatus());
        assertEquals(1, reopened.getReopenCount());
        assertEquals(t.getId(), svc.findActiveByUser("u1").orElseThrow().getId());
    }
}
