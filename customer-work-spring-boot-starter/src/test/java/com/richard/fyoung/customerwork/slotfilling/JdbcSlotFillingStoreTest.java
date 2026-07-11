package com.richard.fyoung.customerwork.slotfilling;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 槽位收集进度存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，见 mysql/schema.sql 中的 cw_slot_filling_progress 表）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；MySQL 在线的机器上真实执行存-取-删往返，
 * 验证 {@link JdbcSlotFillingStore}（含自动建表）。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcSlotFillingStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private JdbcSlotFillingStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setHost(HOST);
        cfg.setPort(PORT);
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");

        DataSource dataSource = new SlotFillingConfig().buildDataSource(cfg);
        store = new JdbcSlotFillingStore(dataSource);
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
    void findOrCreate_shouldReturnTransient_whenNotPersisted() {
        String key = "it-key-" + UUID.randomUUID();
        SlotFillingProgress progress = store.findOrCreate(key);
        assertNull(progress.getAsking());
        assertEquals(0, progress.getCollected().size());
        // 未 save 之前不应落库
        assertFalse(store.find(key).isPresent());
    }

    @Test
    void saveFindDelete_shouldRoundTripCollectedAndAsking() {
        String key = "it-key-" + UUID.randomUUID();
        try {
            SlotFillingProgress progress = new SlotFillingProgress();
            progress.getCollected().put("orderId", "20260613001");
            progress.setAsking("reason");
            store.save(key, progress);

            SlotFillingProgress reloaded = store.find(key).orElseThrow();
            assertEquals("reason", reloaded.getAsking());
            assertEquals("20260613001", reloaded.getCollected().get("orderId"));
        } finally {
            store.delete(key);
        }
        assertFalse(store.find(key).isPresent(), "删除后不应再查到");
    }

    @Test
    void slotFillingConfig_shouldSelectJdbcStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSlotFilling().setStoreMode("jdbc");
        props.getSession().getMysql().setHost(HOST);
        props.getSession().getMysql().setPort(PORT);
        props.getSession().getMysql().setDatabase("agent_scope_customer_work");
        props.getSession().getMysql().setUsername("root");
        props.getSession().getMysql().setPassword("root");

        SlotFillingStore selected = new SlotFillingConfig().slotFillingStore(props);
        assertInstanceOf(JdbcSlotFillingStore.class, selected, "store-mode=jdbc 应装配 JdbcSlotFillingStore");
    }
}
