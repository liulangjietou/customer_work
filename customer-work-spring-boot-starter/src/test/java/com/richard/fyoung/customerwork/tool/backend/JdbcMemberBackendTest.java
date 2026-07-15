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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 会员后端测试（对接本机 MySQL；不可达自动跳过）：种子会员积分/等级文案断言、账户问题落库。
 *
 * <p>种子 U-demo-1 与 {@link MockMemberBackend} 数据一致；resolveAccountIssue 用例在 finally 删除自建日志行。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcMemberBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private DataSource dataSource;
    private JdbcMemberBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = buildDataSource();
        backend = new JdbcMemberBackend(dataSource);
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
        ds.setPoolName("test-member-pool");
        return ds;
    }

    @Test
    void queryPoints_seededMember_shouldMatchMockText() {
        String result = backend.queryPoints("U-demo-1").block();
        assertTrue(result.contains("当前积分 1280 分"), "种子积分应为 1280");
        assertTrue(result.contains("可抵扣 12.80 元"), "1280 积分抵 12.80 元");
        assertTrue(result.contains("本月底将有 200 分到期"), "到期积分 200");
    }

    @Test
    void queryMemberLevel_seededMember_shouldMatchMockText() {
        String result = backend.queryMemberLevel("U-demo-1").block();
        assertTrue(result.contains("黄金会员"), "种子等级黄金会员");
        assertTrue(result.contains("再消费 500.00 元可升级铂金"));
    }

    @Test
    void queryPoints_unknownMember_shouldReturnNotFound() {
        String result = backend.queryPoints("U-not-exist").block();
        assertTrue(result.contains("未查询到会员"));
    }

    @Test
    void resolveAccountIssue_shouldKeepGuideAndPersistLog() throws Exception {
        long before = countAccountLog();
        try {
            String result = backend.resolveAccountIssue("无法登录").block();
            assertTrue(result.contains("验证码登录"), "应保留固定处置话术");
            assertTrue(result.contains("触发风控"));
            assertTrue(countAccountLog() > before, "应落一条处理日志");
        } finally {
            deleteAccountLog("无法登录");
        }
    }

    private long countAccountLog() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM cw_member_account_log");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void deleteAccountLog(String issue) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cw_member_account_log WHERE issue = ?")) {
            ps.setString(1, issue);
            ps.executeUpdate();
        }
    }
}
