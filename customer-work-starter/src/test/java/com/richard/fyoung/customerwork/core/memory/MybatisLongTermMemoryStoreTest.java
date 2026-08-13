package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 长期记忆存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，表 cw_long_term_memory 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）。每个用例用随机 scopeId 隔离，互不干扰，也不留脏数据依赖。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisLongTermMemoryStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisLongTermMemoryStore store;
    private String scopeId;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-ltm-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        LongTermMemoryMapper mapper = MybatisTestSupport.mapper(dataSource, LongTermMemoryMapper.class);
        store = new MybatisLongTermMemoryStore(mapper, 500);
        scopeId = "scope-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (store != null && scopeId != null) {
            store.clear(scopeId);
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void addThenRecall_shouldReturnRelevantFacts() {
        store.add(scopeId, "用户偏好顺丰快递");
        store.add(scopeId, "用户常用收货地址杭州");

        List<String> hits = store.recall(scopeId, "顺丰", 5);

        assertEquals(1, hits.size());
        assertEquals("用户偏好顺丰快递", hits.get(0));
    }

    @Test
    void add_shouldDeduplicateSameFactWithinScope() {
        store.add(scopeId, "用户偏好顺丰快递");
        store.add(scopeId, "用户偏好顺丰快递");
        store.add(scopeId, "  用户偏好顺丰快递  ");  // trim 后同内容，同样算重复

        assertEquals(1, store.size(scopeId), "同分区内相同事实只应留一条");
    }

    @Test
    void recall_shouldIsolateAcrossScopes() {
        String otherScope = "scope-" + UUID.randomUUID();
        store.add(scopeId, "用户偏好顺丰快递");
        try {
            assertTrue(store.recall(otherScope, "顺丰", 5).isEmpty(), "分区隔离：别的分区不该召回本分区事实");
        } finally {
            store.clear(otherScope);
        }
    }

    @Test
    void add_shouldKeepSameFactInDifferentScopes() {
        String otherScope = "scope-" + UUID.randomUUID();
        store.add(scopeId, "用户偏好顺丰快递");
        store.add(otherScope, "用户偏好顺丰快递");
        try {
            assertEquals(1, store.size(scopeId));
            assertEquals(1, store.size(otherScope), "去重键含 scope，不同分区的相同事实不该互相顶掉");
        } finally {
            store.clear(otherScope);
        }
    }

    @Test
    void clear_shouldRemoveAllFactsOfScope() {
        store.add(scopeId, "用户偏好顺丰快递");
        store.add(scopeId, "用户常用收货地址杭州");

        store.clear(scopeId);

        assertEquals(0, store.size(scopeId));
        assertTrue(store.recall(scopeId, "顺丰", 5).isEmpty());
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
