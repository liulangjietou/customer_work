package com.richard.fyoung.customerwork.user;

import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.user.mapper.UserMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 用户账户存储测试（对接本机 MySQL；不可达自动跳过）。
 * 存-取往返验证 {@link MybatisUserAccountStore}（表 cw_user 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 * @author owlzhangfq@gmail.com
 */
class MybatisUserAccountStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisUserAccountStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-user-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        UserMapper mapper = MybatisTestSupport.mapper(dataSource, UserMapper.class);
        store = new MybatisUserAccountStore(mapper);
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
