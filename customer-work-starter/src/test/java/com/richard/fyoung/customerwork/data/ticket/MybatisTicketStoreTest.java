package com.richard.fyoung.customerwork.data.ticket;

import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 工单存储测试（对接本机 MySQL：localhost:3306，root/root，库 agent_scope_customer_work）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；在线时真实执行存-取-改-分页-事件往返与并发抢单语义。
 * 各测试用 UUID 生成唯一 id/session/user，避免与既有数据或并行用例互相污染。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisTicketStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private MybatisTicketStore store;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-ticket-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        store = new MybatisTicketStore(
            MybatisTestSupport.mapper(dataSource, TicketMapper.class),
            MybatisTestSupport.mapper(dataSource, TicketEventMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Ticket waitingAgentTicket(String id, String sessionId, String userId) {
        Ticket t = Ticket.create(id, sessionId, userId, "标题", TicketCategory.ORDER);
        t.requestHandoff("要人工");
        return t;
    }

    @Test
    void saveUpdateFind_shouldRoundTrip() {
        String id = "TK-it-" + UUID.randomUUID();
        Ticket t = waitingAgentTicket(id, "s-" + id, "u-" + id);
        store.save(t);

        Ticket found = store.find(id).orElseThrow();
        assertEquals(TicketStatus.WAITING_AGENT, found.getStatus());
        assertEquals("要人工", found.getHandoffReason());

        found.claim("agent-1");
        store.update(found);
        Ticket claimed = store.find(id).orElseThrow();
        assertEquals(TicketStatus.PROCESSING, claimed.getStatus());
        assertEquals("agent-1", claimed.getAssignee());
        assertTrue(claimed.getClaimedAtMs() > 0);
    }

    @Test
    void findActiveBySession_shouldReturnLatestNonTerminal() {
        String session = "s-active-" + UUID.randomUUID();
        String id = "TK-it-" + UUID.randomUUID();
        store.save(waitingAgentTicket(id, session, "u-" + id));

        assertTrue(store.findActiveBySession(session).isPresent());

        Ticket t = store.find(id).orElseThrow();
        t.cancelHandoff();
        t.close("done");
        store.update(t);
        assertTrue(store.findActiveBySession(session).isEmpty(), "已关闭后不应再算活跃");
    }

    @Test
    void findActiveByUser_shouldReturnLatestNonTerminal() {
        String user = "u-active-" + UUID.randomUUID();
        String id = "TK-it-" + UUID.randomUUID();
        store.save(waitingAgentTicket(id, "s-" + id, user));

        assertTrue(store.findActiveByUser(user).isPresent());

        Ticket t = store.find(id).orElseThrow();
        t.cancelHandoff();
        t.close("done");
        store.update(t);
        assertTrue(store.findActiveByUser(user).isEmpty(), "已关闭后用户不应再算活跃");
    }

    @Test
    void lastUserActive_shouldRoundTrip() {
        String id = "TK-it-" + UUID.randomUUID();
        Ticket t = Ticket.create(id, "s-" + id, "u-" + id, "标题", TicketCategory.CONSULT);
        long created = t.getLastUserActiveAtMs();
        assertTrue(created > 0, "建单即初始化最后活跃时间");
        store.save(t);

        Ticket loaded = store.find(id).orElseThrow();
        assertEquals(created, loaded.getLastUserActiveAtMs(), "最后活跃时间应往返一致");

        loaded.markUserActive();
        store.update(loaded);
        assertTrue(store.find(id).orElseThrow().getLastUserActiveAtMs() >= created, "刷新后应持久化新值");
    }

    @Test
    void findPage_shouldFilterAndPaginate() {
        String user = "u-page-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            String id = "TK-it-" + UUID.randomUUID();
            store.save(Ticket.create(id, "s-" + id, user, "标题" + i, TicketCategory.CONSULT));
        }

        PageResult<Ticket> page1 = store.findPage(new TicketQuery(null, null, null, null, user, 1, 2));
        assertEquals(3, page1.total());
        assertEquals(2, page1.items().size());

        PageResult<Ticket> page2 = store.findPage(new TicketQuery(null, null, null, null, user, 2, 2));
        assertEquals(1, page2.items().size());

        PageResult<Ticket> byStatus = store.findPage(
            new TicketQuery(TicketStatus.AI_SERVING, null, null, null, user, 1, 10));
        assertEquals(3, byStatus.total(), "三张均为 AI_SERVING");
    }

    @Test
    void appendAndFindEvents_shouldRoundTripInOrder() {
        String ticketId = "TK-it-" + UUID.randomUUID();
        store.appendEvent(TicketEvent.of(ticketId, TicketEventType.CREATE, null, TicketStatus.AI_SERVING,
            TicketActorType.SYSTEM, "u1", "建单"));
        store.appendEvent(TicketEvent.of(ticketId, TicketEventType.REQUEST_HANDOFF, TicketStatus.AI_SERVING,
            TicketStatus.WAITING_AGENT, TicketActorType.USER, "u1", "要人工"));

        List<TicketEvent> events = store.findEvents(ticketId);
        assertEquals(2, events.size());
        assertEquals(TicketEventType.CREATE, events.get(0).eventType());
        assertEquals(TicketEventType.REQUEST_HANDOFF, events.get(1).eventType());
        assertTrue(events.get(0).id() < events.get(1).id(), "自增主键应递增");
    }

    @Test
    void claimAtomically_secondCallShouldReturnFalse() {
        String id = "TK-it-" + UUID.randomUUID();
        store.save(waitingAgentTicket(id, "s-" + id, "u-" + id));

        long now = System.currentTimeMillis();
        assertTrue(store.claimAtomically(id, "agent-1", now), "首次抢单应成功");
        assertFalse(store.claimAtomically(id, "agent-2", now), "并发第二次抢单应失败");

        Ticket claimed = store.find(id).orElseThrow();
        assertEquals("agent-1", claimed.getAssignee());
        assertEquals(TicketStatus.PROCESSING, claimed.getStatus());
    }
}
