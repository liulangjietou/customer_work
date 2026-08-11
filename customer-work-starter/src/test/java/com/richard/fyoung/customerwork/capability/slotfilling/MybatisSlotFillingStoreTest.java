package com.richard.fyoung.customerwork.capability.slotfilling;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.slotfilling.mapper.SlotFillingMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 槽位收集进度存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，见 customer-work-schema.sql 中的 cw_slot_filling_progress 表）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；MySQL 在线的机器上真实执行存-取-删往返，
 * 验证 {@link MybatisSlotFillingStore}。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisSlotFillingStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource ds;
    private SlotFillingMapper mapper;
    private MybatisSlotFillingStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        ds = MybatisTestSupport.mysqlDataSource("test-slotfilling-pool");
        MybatisTestSupport.ensureSchema(ds);
        mapper = MybatisTestSupport.mapper(ds, SlotFillingMapper.class);
        store = new MybatisSlotFillingStore(mapper);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) {
            ds.close();
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
    void slotFillingConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSlotFilling().setStoreMode("jdbc");

        SlotFillingStore selected = new SlotFillingConfig().slotFillingStore(props, providerOf(mapper));
        assertInstanceOf(MybatisSlotFillingStore.class, selected, "store-mode=jdbc 应装配 MybatisSlotFillingStore");
    }

    /** 构造一个仅返回给定 Bean 的 {@link ObjectProvider}，供配置类单测注入 Mapper。 */
    private static <T> ObjectProvider<T> providerOf(T bean) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }
        };
    }
}
