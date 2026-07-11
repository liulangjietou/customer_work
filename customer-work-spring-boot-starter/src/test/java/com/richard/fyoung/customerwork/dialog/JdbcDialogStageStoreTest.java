package com.richard.fyoung.customerwork.dialog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 对话阶段存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，见 mysql/schema.sql 中的 cw_dialog_stage 表）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；MySQL 在线的机器上真实执行存-取-删往返，
 * 验证 {@link JdbcDialogStageStore}（含自动建表），并验证跨"实例"（此处用两个独立 store
 * 实例模拟两个应用节点）共享同一份阶段状态。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcDialogStageStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private JdbcDialogStageStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setHost(HOST);
        cfg.setPort(PORT);
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");

        DataSource dataSource = new DialogStageConfig().buildDataSource(cfg);
        store = new JdbcDialogStageStore(dataSource);
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
    void setFindRemove_shouldRoundTrip() {
        String sessionId = "it-session-" + UUID.randomUUID();
        try {
            assertFalse(store.find(sessionId).isPresent(), "未设置前不应查到");

            store.set(sessionId, DialogStage.COLLECTING);
            assertEquals(DialogStage.COLLECTING, store.find(sessionId).orElseThrow());

            store.set(sessionId, DialogStage.PROCESSING);
            assertEquals(DialogStage.PROCESSING, store.find(sessionId).orElseThrow(), "重复 set 应覆盖");
        } finally {
            store.remove(sessionId);
        }
        assertFalse(store.find(sessionId).isPresent(), "删除后不应再查到");
    }

    @Test
    void twoStoreInstances_shouldShareStateAcrossSameMysql() {
        // 模拟两个应用实例：各自持有独立的 JdbcDialogStageStore，但指向同一 MySQL
        JdbcDialogStageStore instanceA = store;
        JdbcDialogStageStore instanceB = new JdbcDialogStageStore(
            new DialogStageConfig().buildDataSource(mysqlConfig()));

        String sessionId = "it-shared-" + UUID.randomUUID();
        try {
            instanceA.set(sessionId, DialogStage.ESCALATED);
            // 实例 B（模拟另一节点收到后续请求）应能读到实例 A 写入的阶段
            assertEquals(DialogStage.ESCALATED, instanceB.find(sessionId).orElseThrow(),
                "多实例应共享同一份对话阶段状态");
        } finally {
            instanceA.remove(sessionId);
        }
    }

    @Test
    void dialogStageConfig_shouldSelectJdbcStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getDialog().setStoreMode("jdbc");
        props.getSession().getMysql().setHost(HOST);
        props.getSession().getMysql().setPort(PORT);
        props.getSession().getMysql().setDatabase("agent_scope_customer_work");
        props.getSession().getMysql().setUsername("root");
        props.getSession().getMysql().setPassword("root");

        DialogStageStore selected = new DialogStageConfig().dialogStageStore(props);
        assertInstanceOf(JdbcDialogStageStore.class, selected, "store-mode=jdbc 应装配 JdbcDialogStageStore");
    }

    private CustomerWorkProperties.Session.Mysql mysqlConfig() {
        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setHost(HOST);
        cfg.setPort(PORT);
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");
        return cfg;
    }
}
