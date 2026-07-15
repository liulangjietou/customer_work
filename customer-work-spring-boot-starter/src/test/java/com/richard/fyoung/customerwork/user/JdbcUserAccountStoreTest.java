package com.richard.fyoung.customerwork.user;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 用户账户存储测试（对接本机 MySQL；不可达自动跳过）。存-取往返验证 {@link JdbcUserAccountStore}（含自动建表）。
 * @author owlzhangfq@gmail.com
 */
class JdbcUserAccountStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private JdbcUserAccountStore store;
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

        dataSource = new UserAccountConfig().buildDataSource(cfg);
        store = new JdbcUserAccountStore(dataSource);
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
    void saveAndFind_shouldRoundTrip() {
        String username = "user-it-" + UUID.randomUUID();
        String id = "U-" + UUID.randomUUID();
        UserAccount account = UserAccount.create(id, username, "$2a$10$hashhashhashhashhashha", "昵称", "13800000000");
        store.save(account);

        UserAccount byName = store.findByUsername(username).orElseThrow();
        assertEquals(id, byName.getId());
        assertEquals(UserAccount.Status.ACTIVE, byName.getStatus());

        UserAccount byId = store.findById(id).orElseThrow();
        assertEquals(username, byId.getUsername());
        assertTrue(byId.isActive());
    }
}
