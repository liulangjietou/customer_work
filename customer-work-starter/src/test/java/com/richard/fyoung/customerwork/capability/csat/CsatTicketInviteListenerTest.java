package com.richard.fyoung.customerwork.capability.csat;

import com.richard.fyoung.customerwork.core.support.OpsScopeResolver;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketEvent;
import com.richard.fyoung.customerwork.data.ticket.TicketEventType;
import com.richard.fyoung.customerwork.data.ticket.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单终态 → 满意度邀请。
 *
 * <p>这条线此前是断的：邀请只挂在 {@code CustomerServiceService#endSession} 上，
 * 而用户端真正的结束动作走工单状态机，压根不经过那里——于是回收率的分母近乎恒为 0，
 * 看板三个指标全是 0.0%，链路本身却不报任何错。</p>
 * @author owlzhangfq@gmail.com
 */
class CsatTicketInviteListenerTest {

    private static final String SESSION_ID = "u42:conv-abc";

    private CsatStore store;
    private CsatService csatService;
    private CsatTicketInviteListener listener;

    @BeforeEach
    void setUp() {
        store = new InMemoryCsatStore();
        csatService = new CsatService(store, new OpsScopeResolver());
        listener = new CsatTicketInviteListener(csatService);
    }

    @Test
    void closed_shouldInvite() {
        listener.onTicketEvent(ticket(), event(TicketEventType.CLOSE, TicketStatus.AI_SERVING, TicketStatus.CLOSED));

        CsatSurvey survey = store.find(SESSION_ID).orElseThrow();
        assertFalse(survey.answered(), "刚邀请时还没有评分，但分母已经记上了");
    }

    @Test
    void resolved_shouldAlsoInvite() {
        // RESOLVED 同样是"这次服务到此为止"，只是收尾方式不同，用户端也据此转只读态
        listener.onTicketEvent(ticket(),
            event(TicketEventType.CONFIRM, TicketStatus.WAITING_CONFIRM, TicketStatus.RESOLVED));

        assertTrue(store.find(SESSION_ID).isPresent());
    }

    @Test
    void nonTerminalTransition_shouldNotInvite() {
        // 转人工只是换了服务方，服务还没结束——这时候邀请评分既没道理也会把分母灌水
        listener.onTicketEvent(ticket(),
            event(TicketEventType.REQUEST_HANDOFF, TicketStatus.AI_SERVING, TicketStatus.WAITING_AGENT));

        assertTrue(store.find(SESSION_ID).isEmpty());
    }

    @Test
    void repeatedDelivery_shouldNotInflateDenominator() {
        // Outbox 是至少一次投递，同一事件可能重复到达；RESOLVED → CLOSED 也会连发两次
        listener.onTicketEvent(ticket(),
            event(TicketEventType.CONFIRM, TicketStatus.WAITING_CONFIRM, TicketStatus.RESOLVED));
        CsatSurvey first = store.find(SESSION_ID).orElseThrow();
        csatService.submit(SESSION_ID, 5, "解决得很快");
        listener.onTicketEvent(ticket(), event(TicketEventType.CLOSE, TicketStatus.RESOLVED, TicketStatus.CLOSED));

        CsatSurvey again = store.find(SESSION_ID).orElseThrow();
        assertEquals(first.invitedAtMs(), again.invitedAtMs(), "重复邀请不该刷新邀请时间");
        assertEquals(5, again.score(), "更不该把用户已提交的评分清掉");
        assertEquals(1, csatService.summary(again.scopeId(), 0L, Long.MAX_VALUE).invited());
    }

    @Test
    void storeFailure_shouldNotBreakTicketFlow() {
        // 满意度是旁路指标，写失败不该影响工单流转与其他监听器
        CsatStore failing = new InMemoryCsatStore() {
            @Override
            public void save(CsatSurvey survey) {
                throw new IllegalStateException("store down");
            }
        };
        CsatTicketInviteListener fragile = new CsatTicketInviteListener(
            new CsatService(failing, new OpsScopeResolver()));

        assertDoesNotThrow(() -> fragile.onTicketEvent(ticket(),
            event(TicketEventType.CLOSE, TicketStatus.AI_SERVING, TicketStatus.CLOSED)));
    }

    private Ticket ticket() {
        return Ticket.create("TK-1", SESSION_ID, "42", "退款咨询", TicketCategory.AFTER_SALE);
    }

    private TicketEvent event(TicketEventType type, TicketStatus from, TicketStatus to) {
        return TicketEvent.of("TK-1", type, from, to, TicketActorType.USER, "42", null);
    }
}
