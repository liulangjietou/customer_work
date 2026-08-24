package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffStatus;
import com.richard.fyoung.customerwork.data.ticket.InMemoryTicketStore;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.data.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工转接工具单测：未注入退化为纯文案兜底；注入 HandoffService 后真实登记；
 * 注入 TicketService + 真实会话后只驱动一张权威工单到 WAITING_AGENT。
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
                assertTrue(result.contains("TK-"), "应包含权威工单号前缀");
                assertTrue(result.contains("涉及大额退款"), "应回带转接原因");
            })
            .verifyComplete();

        // 工单应真实落库、状态为 PENDING（等待坐席接单）
        assertEquals(1, handoffService.list().size());
        assertEquals(HandoffStatus.PENDING, handoffService.list().get(0).getStatus());
        assertEquals("涉及大额退款", handoffService.list().get(0).getReason());
    }

    @Test
    void transferToHuman_withSessionAndTicketService_shouldDriveSingleCanonicalTicket() {
        InMemoryTicketStore store = new InMemoryTicketStore();
        TicketService ticketService = new TicketService(store, null);
        ticketService.createForSession("sess-1", "u1", "标题", TicketCategory.COMPLAINT);

        HandoffService handoffService = new HandoffService(ticketService);
        HumanHandoffTools tools = new HumanHandoffTools(handoffService, ticketService, "sess-1");

        StepVerifier.create(tools.transferToHuman("投诉升级"))
            .assertNext(result -> assertTrue(result.contains("TK-"), "应回带权威工单号"))
            .verifyComplete();

        // 工单域被驱动：活跃工单推进到 WAITING_AGENT
        assertEquals(TicketStatus.WAITING_AGENT,
            ticketService.findActiveBySession("sess-1").orElseThrow().getStatus());
        // /handoffs 兼容读模型来自同一张 Ticket，不存在第二次写入
        assertEquals(1, handoffService.list().size());
        assertEquals("sess-1", handoffService.list().get(0).getSessionId());
    }

    @Test
    void transferToHuman_sessionWithoutActiveTicket_shouldNotBreakReply() {
        InMemoryTicketStore store = new InMemoryTicketStore();
        TicketService ticketService = new TicketService(store, null);
        // 未建单：driveTicketDomain 内部 requestHandoff 抛异常应被吞掉，不阻断话术
        HandoffService handoffService = new HandoffService(ticketService);
        HumanHandoffTools tools = new HumanHandoffTools(handoffService, ticketService, "sess-empty");

        StepVerifier.create(tools.transferToHuman("要人工"))
            .assertNext(result -> assertTrue(result.contains("人工坐席")))
            .verifyComplete();

        // 无活跃工单时在同一权威域补建并转人工
        assertEquals(1, handoffService.list().size());
        assertEquals(TicketStatus.WAITING_AGENT,
            ticketService.findActiveBySession("sess-empty").orElseThrow().getStatus());
    }
}
