package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.richard.fyoung.customerwork.infra.config.properties.SessionProperties;

/**
 * MySQL 会话持久化测试（对接本机 MySQL：localhost:3306，root/root，库 agent_scope_customer_work）。
 *
 * <p>当 MySQL 不可达时<b>自动跳过</b>（assumeTrue），保证 {@code mvn test} 在无 MySQL 的环境
 * 仍然通过；在 MySQL 在线的机器上则真实执行存-取-删往返，验证 {@code MysqlSession}（含自动建库建表）。</p>
 * @author owlzhangfq@gmail.com
 */
class MysqlSessionPersistenceTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private AgentStateStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(SessionPersistenceTestSupport.reachable(HOST, PORT),
            "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        SessionProperties cfg = new CustomerWorkProperties().getSession();
        cfg.setMode("mysql");
        cfg.getMysql().setHost(HOST);
        cfg.getMysql().setPort(PORT);
        cfg.getMysql().setDatabase("agent_scope_customer_work");
        cfg.getMysql().setUsername("root");
        cfg.getMysql().setPassword("root");
        cfg.getMysql().setAutoCreate(true);

        store = new SessionConfig().buildStateStore(cfg);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void saveGetDelete_overMysql() {
        SessionPersistenceTestSupport.assertSaveGetDelete(store, "it-mysql:" + UUID.randomUUID());
    }
}
