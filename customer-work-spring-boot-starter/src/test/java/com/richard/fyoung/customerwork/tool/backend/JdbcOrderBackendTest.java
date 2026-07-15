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
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 订单后端测试（对接本机 MySQL；不可达自动跳过）：种子文案断言 + modifyAddress 真实落库。
 *
 * <p>种子文案与 {@link MockOrderBackend} 一致，验证从 Mock 切到 JDBC 后系统提示词示例连续。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcOrderBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private DataSource dataSource;
    private JdbcOrderBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = buildDataSource();
        backend = new JdbcOrderBackend(dataSource);
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
        cfg.setUsername("root");
        cfg.setPassword("root");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.resolveJdbcUrl());
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setMaximumPoolSize(3);
        ds.setPoolName("test-order-pool");
        return ds;
    }

    @Test
    void queryOrder_seededDemoOrder_shouldMatchMockText() {
        String result = backend.queryOrder("20260613001").block();
        assertEquals("订单 20260613001：状态=已发货，金额=299.00 元，下单时间=2026-06-10。", result);
    }

    @Test
    void queryLogistics_seededDemoOrder_shouldReturnTrace() {
        String result = backend.queryLogistics("20260613001").block();
        assertEquals("订单 20260613001 物流：[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。", result);
    }

    @Test
    void queryOrder_unknownOrder_shouldReturnNotFound() {
        String result = backend.queryOrder("99999999999").block();
        assertTrue(result.contains("未查询到订单"));
    }

    @Test
    void modifyAddress_shouldReallyPersist() throws Exception {
        String newAddr = "验证地址-" + UUID.randomUUID();
        String result = backend.modifyAddress("20260613003", newAddr).block();
        assertTrue(result.contains("已更新为「" + newAddr + "」"), "应返回更新成功文案");

        assertEquals(newAddr, readAddr("20260613003"), "收货地址应真实落库");
    }

    private String readAddr(String orderId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT receiver_addr FROM cw_order WHERE order_id = ?")) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
