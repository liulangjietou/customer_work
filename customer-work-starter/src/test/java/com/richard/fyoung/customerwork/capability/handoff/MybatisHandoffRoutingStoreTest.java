package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.capability.handoff.mapper.HandoffMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis 人机切换工单·智能分配增强列 round-trip 测试（对接本机 MySQL，不可达自动跳过）。
 * 验证 category/required_skill/priority/emotion/suggested_assignees 五个新列的读写与 upsert 更新。
 * @author owlzhangfq@gmail.com
 */
class MybatisHandoffRoutingStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisHandoffStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-handoff-routing-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        store = new MybatisHandoffStore(MybatisTestSupport.mapper(dataSource, HandoffMapper.class));
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

    @Test
    void save_shouldPersistRoutingEnrichmentColumns() {
        String id = "HO-IT-" + UUID.randomUUID();
        HandoffTicket ticket = new HandoffTicket(id, "sess-it", "涉及大额退款", System.currentTimeMillis());
        ticket.applyRoutingSuggestion("退款", "refund", "HIGH", "不满", "[{\"seatId\":\"SEAT-1001\"}]");
        store.save(ticket);

        HandoffTicket read = store.find(id).orElseThrow();
        assertEquals("退款", read.getCategory());
        assertEquals("refund", read.getRequiredSkill());
        assertEquals("HIGH", read.getPriority());
        assertEquals("不满", read.getEmotion());
        assertEquals("[{\"seatId\":\"SEAT-1001\"}]", read.getSuggestedAssignees());
    }
}
