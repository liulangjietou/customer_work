package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.infra.config.properties.SessionProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 演示订单播种测试（对接本机 MySQL；不可达自动跳过）：真实往返插入 2 笔订单，测试后删除自建数据。
 *
 * <p>先执行 starter 的完整 Flyway 迁移，确保 cw_product（P001/P002 种子）与 cw_order 表就绪，
 * 再验证播种结果的状态/物流轨迹/商品名对齐。</p>
 * @author owlzhangfq@gmail.com
 */
class DemoOrderSeederTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private DemoOrderSeeder seeder;
    private String userId;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = buildDataSource();
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
        seeder = new DemoOrderSeeder(providerOf(dataSource));
        userId = "U-seedtest-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            cleanup();
            dataSource.close();
        }
    }

    @Test
    void seedForNewUser_shouldInsertTwoDemoOrders() {
        seeder.seedForNewUser(userId);

        List<String[]> rows = queryRows();
        assertEquals(2, rows.size(), "应播种 2 笔演示订单");

        String[] shipped = rowByStatus(rows, "已发货");
        assertNotNull(shipped, "应有一笔已发货订单");
        assertEquals("P001", shipped[1]);
        assertEquals("旗舰款无线降噪耳机", shipped[2]);
        assertEquals("299.00", shipped[3]);
        assertNotNull(shipped[5], "已发货订单应带物流轨迹");
        assertTrue(shipped[0].startsWith("20"), "订单号应为 yyyyMMdd 前缀");
        assertEquals(13, shipped[0].length(), "订单号应为 8 位日期 + 5 位随机数字");

        String[] pending = rowByStatus(rows, "待发货");
        assertNotNull(pending, "应有一笔待发货订单");
        assertEquals("P002", pending[1]);
        assertEquals("运动防汗蓝牙耳机", pending[2]);
        assertEquals("199.00", pending[3]);
        assertNull(pending[5], "待发货订单无物流轨迹");
    }

    private List<String[]> queryRows() {
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT order_id, product_id, product_name, amount, status, logistics_trace "
                 + "FROM cw_order WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{
                        rs.getString("order_id"),
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("amount").setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                        rs.getString("status"),
                        rs.getString("logistics_trace"),
                    });
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("query seeded orders failed", e);
        }
        return rows;
    }

    /** rows 元素布局：[orderId, productId, productName, amount, status, logisticsTrace]。 */
    private static String[] rowByStatus(List<String[]> rows, String status) {
        return rows.stream().filter(r -> status.equals(r[4])).findFirst().orElse(null);
    }

    private void cleanup() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cw_order WHERE user_id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (Exception e) {
            // 清理失败不影响测试结论，忽略
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

    private static HikariDataSource buildDataSource() {
        SessionProperties.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setDatabase(System.getenv().getOrDefault(
            "CUSTOMER_WORK_TEST_DATABASE", "agent_scope_customer_work"));
        cfg.setUsername("root");
        cfg.setPassword("root");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.resolveJdbcUrl());
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setMaximumPoolSize(3);
        ds.setPoolName("test-demo-seeder-pool");
        return ds;
    }

    /** 用真实 bean factory 造一个 ObjectProvider<DataSource>（版本无关，避免手写多方法接口）。 */
    private static ObjectProvider<DataSource> providerOf(DataSource ds) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("toolBackendDataSource", ds);
        return bf.getBeanProvider(DataSource.class);
    }
}
