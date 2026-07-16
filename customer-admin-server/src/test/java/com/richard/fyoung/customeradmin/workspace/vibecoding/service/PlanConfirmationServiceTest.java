package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService.PlanChannel;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService.PlanTicket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanConfirmationService} 单测：通道生命周期、提交挂起、确认/拒绝、无通道 fast fail、事件下发。
 * @author owlzhangfq@gmail.com
 */
class PlanConfirmationServiceTest {

    private final PlanConfirmationService service = new PlanConfirmationService();

    private PlanEvent planEvent(String planId) {
        return new PlanEvent(planId, List.of(new PlanAction("RUN_COMMAND", "rm -rf x", "执行破坏性命令")), "执行破坏性命令", true, 300);
    }

    @Test
    void submit_shouldReturnEmpty_whenNoChannel() {
        assertTrue(service.submit("coder", "s1", planEvent("p1")).isEmpty(), "无活跃通道时应 fast fail 返回空");
    }

    @Test
    void submit_shouldEmitPlanEvent_andRegisterPending() {
        PlanChannel channel = service.openChannel("coder", "s1");
        List<ChatStreamChunk> events = new CopyOnWriteArrayList<>();
        service.events(channel).subscribe(events::add);

        Optional<PlanTicket> ticket = service.submit("coder", "s1", planEvent("p1"));

        assertTrue(ticket.isPresent());
        assertFalse(ticket.get().future().isDone(), "提交后 future 应处于挂起态");
        assertEquals(1, events.size());
        assertEquals(ChatNodeKind.PLAN, events.get(0).kind());
    }

    @Test
    void confirm_shouldCompleteFutureTrue_andEmitPlanResult_whenApproved() throws Exception {
        PlanChannel channel = service.openChannel("coder", "s1");
        List<ChatStreamChunk> events = new CopyOnWriteArrayList<>();
        service.events(channel).subscribe(events::add);
        PlanTicket ticket = service.submit("coder", "s1", planEvent("p1")).orElseThrow();

        boolean resolved = service.confirm("coder", "s1", "p1", true);

        assertTrue(resolved);
        assertEquals(Boolean.TRUE, ticket.future().get());
        assertTrue(events.stream().anyMatch(e -> e.kind() == ChatNodeKind.PLAN_RESULT));
    }

    @Test
    void confirm_shouldCompleteFutureFalse_whenRejected() throws Exception {
        PlanChannel channel = service.openChannel("coder", "s1");
        service.events(channel).subscribe();
        PlanTicket ticket = service.submit("coder", "s1", planEvent("p1")).orElseThrow();

        assertTrue(service.confirm("coder", "s1", "p1", false));
        assertEquals(Boolean.FALSE, ticket.future().get());
    }

    @Test
    void confirm_shouldReturnFalse_forUnknownPlanId() {
        service.openChannel("coder", "s1");
        assertFalse(service.confirm("coder", "s1", "does-not-exist", true));
    }

    @Test
    void confirm_shouldReturnFalse_forCrossSession() {
        PlanChannel channel = service.openChannel("coder", "s1");
        service.events(channel).subscribe();
        service.submit("coder", "s1", planEvent("p1")).orElseThrow();
        // 另一个会话的确认不能命中本会话的挂起项（会话归属校验）
        assertFalse(service.confirm("coder", "s2", "p1", true));
    }

    @Test
    void closeChannel_shouldAbortPending_andCompleteEvents() {
        PlanChannel channel = service.openChannel("coder", "s1");
        List<ChatStreamChunk> events = new CopyOnWriteArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        service.events(channel).subscribe(events::add, err -> { }, () -> completed.set(true));
        PlanTicket ticket = service.submit("coder", "s1", planEvent("p1")).orElseThrow();

        service.closeChannel(channel);

        assertTrue(ticket.future().isDone(), "关闭通道应拒绝并完成残留挂起项（fast fail）");
        assertEquals(1, events.size(), "订阅应收到 plan 事件");
        assertTrue(completed.get(), "通道关闭后事件流应正常结束");
        // 关闭后再确认已 fast fail
        assertFalse(service.confirm("coder", "s1", "p1", true));
    }

    /**
     * timeout 幂等收口回归：超时边界时刻用户抢先 confirm 后，timeout 不得再补发 TIMEOUT——
     * 否则前端先收 APPROVED 又收 TIMEOUT，终态矛盾。
     */
    @Test
    void timeout_shouldNotEmit_whenAlreadyConfirmed() {
        PlanChannel channel = service.openChannel("coder", "s1");
        List<ChatStreamChunk> events = new CopyOnWriteArrayList<>();
        service.events(channel).subscribe(events::add);
        PlanTicket ticket = service.submit("coder", "s1", planEvent("p1")).orElseThrow();

        assertTrue(service.confirm("coder", "s1", "p1", true));
        service.timeout(ticket);

        // 只应有 plan + plan_result(APPROVED) 两条，timeout 不追加第三条
        assertEquals(2, events.size(), "已确认后 timeout 不应再补发 TIMEOUT 事件");
        long resultCount = events.stream().filter(e -> e.kind() == ChatNodeKind.PLAN_RESULT).count();
        assertEquals(1, resultCount, "plan_result 终态只应出现一次");
    }
}
