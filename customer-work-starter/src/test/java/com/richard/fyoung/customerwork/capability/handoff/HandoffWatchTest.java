package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.data.ticket.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HandoffService#watch}：观察窗口打开期间，该会话上的每一次转人工请求都要被记下。
 *
 * <p>语义缓存据此判断「这一轮有没有转人工」——命中缓存时 Agent 不会运行，
 * 一句「已为您转接人工客服」被原样重放给下一个人，背后却没有任何转接。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class HandoffWatchTest {

    private static final String SESSION = "u1:conv-1";
    private static final String OTHER_SESSION = "u2:conv-9";

    private final HandoffService service = new HandoffService();

    @Test
    @DisplayName("窗口内同一会话转人工：记下")
    void handoffOnWatchedSession_shouldBeRecorded() {
        try (HandoffWatch watch = service.watch(SESSION)) {
            service.create(SESSION, "用户要求人工");

            assertTrue(watch.requested());
        }
    }

    @Test
    @DisplayName("别的会话转人工：不记")
    void handoffOnOtherSession_shouldNotBeRecorded() {
        try (HandoffWatch watch = service.watch(SESSION)) {
            service.create(OTHER_SESSION, "用户要求人工");

            assertFalse(watch.requested());
        }
    }

    @Test
    @DisplayName("窗口打开之前发生的转人工（上一轮）不算这一轮的")
    void handoffBeforeWatchOpened_shouldNotBeRecorded() {
        service.create(SESSION, "上一轮转的");

        try (HandoffWatch watch = service.watch(SESSION)) {
            assertFalse(watch.requested());
        }
    }

    @Test
    @DisplayName("窗口关闭后发生的转人工不再记入，已记下的结论不随关闭消失")
    void closedWatch_shouldKeepVerdictButStopRecording() {
        HandoffWatch recorded = service.watch(SESSION);
        service.create(SESSION, "本轮转的");
        recorded.close();

        HandoffWatch quiet = service.watch(SESSION);
        quiet.close();
        service.create(SESSION, "下一轮转的");

        assertTrue(recorded.requested(), "关闭只是注销登记，本轮的结论要留到缓存判定时读");
        assertFalse(quiet.requested(), "关闭之后的转人工不属于那一轮");
    }

    @Test
    @DisplayName("同一会话的多个窗口（并发的两轮）都记下：宁可少缓存，不能漏记")
    void overlappingWatchesOnSameSession_shouldAllBeRecorded() {
        try (HandoffWatch first = service.watch(SESSION); HandoffWatch second = service.watch(SESSION)) {
            service.create(SESSION, "用户要求人工");

            assertTrue(first.requested());
            assertTrue(second.requested());
        }
    }

    @Test
    @DisplayName("已在人工链路上的会话再次转人工（工单状态不变）同样记下：答复里照样是那句「已为您转接」")
    void repeatedHandoffWithoutStateChange_shouldStillBeRecorded() {
        service.create(SESSION, "第一次");

        try (HandoffWatch watch = service.watch(SESSION)) {
            service.create(SESSION, "第二次");

            assertTrue(watch.requested());
        }
    }

    @Test
    @DisplayName("建单失败也记下：尝试转过人工的答复同样不能原样重放")
    void failedHandoff_shouldStillBeRecorded() {
        TicketService broken = mock(TicketService.class);
        when(broken.findActiveBySession(anyString())).thenThrow(new IllegalStateException("db down"));
        when(broken.createForSession(anyString(), anyString(), anyString(), any()))
            .thenThrow(new IllegalStateException("db down"));
        HandoffService failing = new HandoffService(broken);

        try (HandoffWatch watch = failing.watch(SESSION)) {
            assertThrows(IllegalStateException.class, () -> failing.create(SESSION, "用户要求人工"));

            assertTrue(watch.requested());
        }
    }

    @Test
    @DisplayName("游离窗口（没有转人工服务可观察）恒为未转人工")
    void detachedWatch_shouldNeverBeRecorded() {
        try (HandoffWatch watch = HandoffWatch.detached()) {
            service.create(SESSION, "用户要求人工");

            assertFalse(watch.requested());
        }
    }
}
