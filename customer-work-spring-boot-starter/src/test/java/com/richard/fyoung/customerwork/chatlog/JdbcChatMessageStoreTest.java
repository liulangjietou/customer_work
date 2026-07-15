package com.richard.fyoung.customerwork.chatlog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.ticket.TicketActorType;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 聊天消息存储测试（对接本机 MySQL；不可达自动跳过）：追加往返 + 游标翻页语义。
 * @author owlzhangfq@gmail.com
 */
class JdbcChatMessageStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private JdbcChatMessageStore store;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setHost(HOST);
        cfg.setPort(PORT);
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");

        dataSource = new ChatLogConfig().buildDataSource(cfg);
        store = new JdbcChatMessageStore(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
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

    @Test
    void appendAndFind_shouldRoundTripWithCursor() {
        String session = "s-it-" + UUID.randomUUID();
        long[] ids = new long[5];
        for (int i = 0; i < 5; i++) {
            ChatMessage saved = store.append(ChatMessage.of("MSG-" + UUID.randomUUID(), session, null,
                TicketActorType.USER, "u1", "msg" + (i + 1)));
            assertTrue(saved.id() > 0, "自增主键应回填");
            ids[i] = saved.id();
        }

        List<ChatMessage> latest2 = store.findBySession(session, null, 2);
        assertEquals(2, latest2.size());
        assertEquals("msg4", latest2.get(0).content(), "升序返回最新两条");
        assertEquals("msg5", latest2.get(1).content());

        List<ChatMessage> older = store.findBySession(session, ids[3], 2);
        assertEquals(2, older.size());
        assertEquals("msg2", older.get(0).content());
        assertEquals("msg3", older.get(1).content());
    }

    @Test
    void findByTicket_shouldFilterByTicketDimension() {
        String session = "s-it-" + UUID.randomUUID();
        String ticketId = "TK-it-" + UUID.randomUUID();
        store.append(ChatMessage.of("MSG-" + UUID.randomUUID(), session, ticketId,
            TicketActorType.AGENT, "agent-1", "工单内消息"));
        store.append(ChatMessage.of("MSG-" + UUID.randomUUID(), session, null,
            TicketActorType.BOT, null, "纯AI消息"));

        List<ChatMessage> byTicket = store.findByTicket(ticketId, null, 10);
        assertEquals(1, byTicket.size());
        assertEquals("工单内消息", byTicket.get(0).content());
    }
}
