package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
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
 * MyBatis-Plus 事实日志测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，表 cw_fact_log 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）。覆盖 FactLog 的行为契约——
 * 顺序、分区隔离、可禁用——外加只有 jdbc 分支才有的读取上限。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisFactLogTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private FactLogMapper mapper;
    private String scopeId;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-factlog-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        mapper = MybatisTestSupport.mapper(dataSource, FactLogMapper.class);
        scopeId = "scope-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void appendThenRead_shouldPersistInWriteOrder() {
        FactLog factLog = new MybatisFactLog(mapper, true, 10000);
        factLog.append(scopeId, "用户偏好顺丰快递");
        factLog.append(scopeId, "用户常用收货地址杭州");

        List<String> facts = factLog.read(scopeId);

        assertEquals(2, facts.size());
        assertEquals("用户偏好顺丰快递", facts.get(0), "读取应还原写入顺序");
        assertEquals("用户常用收货地址杭州", facts.get(1));
        assertEquals(facts, factLog.readForSubjectAccess(scopeId, 10));
    }

    @Test
    void read_shouldIsolateAcrossScopes() {
        FactLog factLog = new MybatisFactLog(mapper, true, 10000);
        factLog.append(scopeId, "用户偏好顺丰快递");

        assertTrue(factLog.read("scope-" + UUID.randomUUID()).isEmpty(), "分区隔离：别的分区不该读到本分区事实");
    }

    @Test
    void append_shouldDoNothing_whenDisabled() {
        FactLog factLog = new MybatisFactLog(mapper, false, 10000);
        factLog.append(scopeId, "用户偏好顺丰快递");

        assertTrue(factLog.read(scopeId).isEmpty(), "enabled=false 时不应写入");
    }

    @Test
    void readRecords_shouldCarryTimestampAndScope() {
        FactLog factLog = new MybatisFactLog(mapper, true, 10000);
        long before = System.currentTimeMillis();
        factLog.append(scopeId, "用户偏好顺丰快递");

        List<FactRecord> records = factLog.readRecords(scopeId);

        assertEquals(1, records.size());
        assertEquals("用户偏好顺丰快递", records.get(0).fact());
        assertEquals(scopeId, records.get(0).scope());
        assertTrue(records.get(0).ts() >= before, "时间戳应是写入时刻");
    }

    @Test
    void read_shouldKeepMostRecent_whenExceedingReadLimit() {
        FactLog writer = new MybatisFactLog(mapper, true, 10000);
        writer.append(scopeId, "第一条");
        writer.append(scopeId, "第二条");
        writer.append(scopeId, "第三条");

        List<String> facts = new MybatisFactLog(mapper, true, 2).read(scopeId);

        assertEquals(List.of("第二条", "第三条"), facts, "超限时保留最近 N 条，且仍是写入顺序");
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
