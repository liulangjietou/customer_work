package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.tool.backend.entity.ComplaintDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.ComplaintMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis 投诉后端测试（对接本机 MySQL；不可达自动跳过）：工单落库往返、种子工单查询、未知工单文案。
 *
 * <p>fileComplaint 用例使用随机订单号，finally 删除自建 cw_complaint 行，绝不污染种子工单。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisComplaintBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private ComplaintMapper complaintMapper;
    private MybatisComplaintBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-complaint-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        complaintMapper = MybatisTestSupport.mapper(dataSource, ComplaintMapper.class);
        backend = new MybatisComplaintBackend(complaintMapper);
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
    void fileComplaint_shouldPersistAndReturnTicketNo() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String result = backend.fileComplaint(orderId, "客服态度差").block();
            assertTrue(result.contains("已为您创建投诉工单"), "应创建投诉工单");
            assertTrue(result.contains("24 小时内跟进处理"));
        } finally {
            deleteComplaintByOrder(orderId);
        }
    }

    @Test
    void queryComplaint_seededTicket_shouldReportProcessing() {
        String result = backend.queryComplaint("CP-seed-0001").block();
        assertTrue(result.contains("处理中"), "种子工单状态为处理中");
    }

    @Test
    void queryComplaint_unknownTicket_shouldReturnNotFound() {
        String result = backend.queryComplaint("CP-not-exist").block();
        assertTrue(result.contains("未查询到投诉工单"));
    }

    private void deleteComplaintByOrder(String orderId) {
        complaintMapper.delete(new LambdaQueryWrapper<ComplaintDO>().eq(ComplaintDO::getOrderId, orderId));
    }
}
