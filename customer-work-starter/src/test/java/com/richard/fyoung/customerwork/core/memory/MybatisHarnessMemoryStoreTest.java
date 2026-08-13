package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.HarnessMemoryMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
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
 * MyBatis-Plus Harness 分层记忆存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，表 cw_harness_memory 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）。重点验证 upsert 语义（同一 workspace 恒定一行）
 * 与长路径 scopeId——后者正是 {@code scope_hash} 存在的理由。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisHarnessMemoryStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisHarnessMemoryStore store;
    private String scopeId;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-harness-memory-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        HarnessMemoryMapper mapper = MybatisTestSupport.mapper(dataSource, HarnessMemoryMapper.class);
        store = new MybatisHarnessMemoryStore(mapper);
        scopeId = "/tmp/workspace-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (store != null && scopeId != null) {
            store.delete(scopeId);
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void load_shouldReturnEmpty_whenNeverSaved() {
        assertTrue(store.load(scopeId).isEmpty());
    }

    @Test
    void saveThenLoad_shouldRoundTrip() {
        store.save(scopeId, "# MEMORY\n- 用户偏好顺丰快递\n");

        assertEquals("# MEMORY\n- 用户偏好顺丰快递\n", store.load(scopeId).orElse(null));
    }

    @Test
    void save_shouldOverwrite_keepingSingleRow() {
        store.save(scopeId, "第一版");
        store.save(scopeId, "第二版");

        assertEquals("第二版", store.load(scopeId).orElse(null), "同一 workspace 应 upsert 覆盖而非追加");
    }

    @Test
    void saveThenLoad_shouldWorkWithLongScopePath() {
        // 长路径是 scope_hash 存在的理由：直接给 512 字节的 VARCHAR 建唯一索引会撞 InnoDB 索引长度上限
        String longScope = "/very/deep/nested/path/" + "segment/".repeat(50) + UUID.randomUUID();
        try {
            store.save(longScope, "长路径记忆");
            assertEquals("长路径记忆", store.load(longScope).orElse(null));
        } finally {
            store.delete(longScope);
        }
    }

    @Test
    void delete_shouldRemoveMemory() {
        store.save(scopeId, "待删除");

        store.delete(scopeId);

        assertTrue(store.load(scopeId).isEmpty());
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
