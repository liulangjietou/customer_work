package com.richard.fyoung.customerwork.dialog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.dialog.mapper.DialogStageMapper;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 对话阶段存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，见 customer-work-schema.sql 中的 cw_dialog_stage 表）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；MySQL 在线的机器上真实执行存-取-删往返，
 * 验证 {@link MybatisDialogStageStore}，并验证跨"实例"（此处用两个独立 store 实例模拟两个应用节点）
 * 共享同一份阶段状态。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisDialogStageStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource ds;
    private DialogStageMapper mapper;
    private MybatisDialogStageStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        ds = MybatisTestSupport.mysqlDataSource("test-dialogstage-pool");
        MybatisTestSupport.ensureSchema(ds);
        mapper = MybatisTestSupport.mapper(ds, DialogStageMapper.class);
        store = new MybatisDialogStageStore(mapper);
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
        // 模拟两个应用实例：各自持有独立的 MybatisDialogStageStore（独立数据源/Mapper），但指向同一 MySQL
        MybatisDialogStageStore instanceA = store;
        HikariDataSource dsB = MybatisTestSupport.mysqlDataSource("test-dialogstage-pool-b");
        try {
            MybatisTestSupport.ensureSchema(dsB);
            DialogStageMapper mapperB = MybatisTestSupport.mapper(dsB, DialogStageMapper.class);
            MybatisDialogStageStore instanceB = new MybatisDialogStageStore(mapperB);

            String sessionId = "it-shared-" + UUID.randomUUID();
            try {
                instanceA.set(sessionId, DialogStage.ESCALATED);
                // 实例 B（模拟另一节点收到后续请求）应能读到实例 A 写入的阶段
                assertEquals(DialogStage.ESCALATED, instanceB.find(sessionId).orElseThrow(),
                    "多实例应共享同一份对话阶段状态");
            } finally {
                instanceA.remove(sessionId);
            }
        } finally {
            dsB.close();
        }
    }

    @Test
    void dialogStageConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getDialog().setStoreMode("jdbc");

        DialogStageStore selected = new DialogStageConfig().dialogStageStore(props, providerOf(mapper));
        assertInstanceOf(MybatisDialogStageStore.class, selected, "store-mode=jdbc 应装配 MybatisDialogStageStore");
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
