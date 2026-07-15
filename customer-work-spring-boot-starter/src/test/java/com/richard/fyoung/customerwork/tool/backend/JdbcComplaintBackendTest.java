package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 投诉后端测试（对接本机 MySQL；不可达自动跳过）：工单落库往返、种子工单查询、未知工单文案。
 *
 * <p>fileComplaint 用例使用随机订单号，finally 删除自建 cw_complaint 行，绝不污染种子工单。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcComplaintBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private DataSource dataSource;
    private JdbcComplaintBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = buildDataSource();
        backend = new JdbcComplaintBackend(dataSource);
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

    private DataSource buildDataSource() {
        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setDatabase("agent_scope_customer_work");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.resolveJdbcUrl());
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setMaximumPoolSize(3);
        ds.setPoolName("test-complaint-pool");
        return ds;
    }

    @Test
    void fileComplaint_shouldPersistAndReturnTicketNo() throws Exception {
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

    private void deleteComplaintByOrder(String orderId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cw_complaint WHERE order_id = ?")) {
            ps.setString(1, orderId);
            ps.executeUpdate();
        }
    }
}
