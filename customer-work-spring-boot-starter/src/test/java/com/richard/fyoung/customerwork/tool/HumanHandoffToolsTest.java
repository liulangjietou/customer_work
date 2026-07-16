package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.handoff.HandoffService;
import com.richard.fyoung.customerwork.handoff.HandoffStatus;
import com.richard.fyoung.customerwork.ticket.InMemoryTicketStore;
import com.richard.fyoung.customerwork.ticket.TicketCategory;
import com.richard.fyoung.customerwork.ticket.TicketService;
import com.richard.fyoung.customerwork.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工转接工具单测：未注入退化为纯文案兜底；注入 HandoffService 后真实登记；
 * 注入 TicketService + 真实会话后驱动工单域到 WAITING_AGENT 并与 handoff 双写。
 * @author owlzhangfq@gmail.com
 */
class HumanHandoffToolsTest {

    @Test
    void transferToHuman_withoutHandoffService_shouldReturnFallbackWorkOrder() {
        HumanHandoffTools tools = new HumanHandoffTools();
        StepVerifier.create(tools.transferToHuman("用户投诉升级"))
            .assertNext(result -> {
                assertTrue(result.contains("人工坐席"), "应提示已转人工");
                assertTrue(result.contains("用户投诉升级"), "应回带转接原因");
                assertTrue(result.contains("HO"), "应包含工单号");
            })
            .verifyComplete();
    }

    @Test
    void transferToHuman_withHandoffService_shouldCreateRealTicket() {
        HandoffService handoffService = new HandoffService();
        HumanHandoffTools tools = new HumanHandoffTools(handoffService);

        StepVerifier.create(tools.transferToHuman("涉及大额退款"))
            .assertNext(result -> {
                assertTrue(result.contains("HO-"), "应包含真实工单号前缀");
                assertTrue(result.contains("涉及大额退款"), "应回带转接原因");
            })
            .verifyComplete();

        // 工单应真实落库、状态为 PENDING（等待坐席接单）
        assertEquals(1, handoffService.list().size());
        assertEquals(HandoffStatus.PENDING, handoffService.list().get(0).getStatus());
        assertEquals("涉及大额退款", handoffService.list().get(0).getReason());
    }

    @Test
    void transferToHuman_withSessionAndTicketService_shouldDriveTicketDomainAndDoubleWrite() {
        InMemoryTicketStore store = new InMemoryTicketStore();
        TicketService ticketService = new TicketService(store, null);
        ticketService.createForSession("sess-1", "u1", "标题", TicketCategory.COMPLAINT);

        HandoffService handoffService = new HandoffService();
        HumanHandoffTools tools = new HumanHandoffTools(handoffService, ticketService, "sess-1");

        StepVerifier.create(tools.transferToHuman("投诉升级"))
            .assertNext(result -> assertTrue(result.contains("HO-"), "应回带 handoff 工单号"))
            .verifyComplete();

        // 工单域被驱动：活跃工单推进到 WAITING_AGENT
        assertEquals(TicketStatus.WAITING_AGENT,
            ticketService.findActiveBySession("sess-1").orElseThrow().getStatus());
        // handoff 双写：会话号用真实 sessionId
        assertEquals(1, handoffService.list().size());
        assertEquals("sess-1", handoffService.list().get(0).getSessionId());
    }

    @Test
    void transferToHuman_sessionWithoutActiveTicket_shouldNotBreakReply() {
        InMemoryTicketStore store = new InMemoryTicketStore();
        TicketService ticketService = new TicketService(store, null);
        // 未建单：driveTicketDomain 内部 requestHandoff 抛异常应被吞掉，不阻断话术
        HandoffService handoffService = new HandoffService();
        HumanHandoffTools tools = new HumanHandoffTools(handoffService, ticketService, "sess-empty");

        StepVerifier.create(tools.transferToHuman("要人工"))
            .assertNext(result -> assertTrue(result.contains("人工坐席")))
            .verifyComplete();

        // handoff 双写仍应发生
        assertEquals(1, handoffService.list().size());
    }
}
