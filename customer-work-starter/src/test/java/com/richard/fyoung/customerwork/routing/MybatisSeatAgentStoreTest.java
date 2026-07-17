package com.richard.fyoung.customerwork.routing;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.routing.mapper.SeatAgentMapper;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 坐席库存储测试（对接本机 MySQL：localhost:3306，root/root，库 agent_scope_customer_work，
 * 表 cw_seat_agent 由 {@link MybatisTestSupport#ensureSchema} 建好）。MySQL 不可达时自动跳过（assumeTrue）。
 * @author owlzhangfq@gmail.com
 */
class MybatisSeatAgentStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisSeatAgentStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-seat-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        store = new MybatisSeatAgentStore(MybatisTestSupport.mapper(dataSource, SeatAgentMapper.class));
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
    void saveAndFindAll_shouldRoundTripWithSkills() {
        String id = "IT-SEAT-" + UUID.randomUUID();
        store.save(new SeatAgent(id, "集成测试坐席", Set.of("refund", "invoice"), 5, 2, true, "aftersales"));

        assertTrue(store.findAll().stream().anyMatch(s ->
            id.equals(s.getId()) && s.hasSkill("refund") && s.hasSkill("invoice")
                && s.getCurrentLoad() == 2 && s.isOnline()));
    }

    @Test
    void seeds_shouldBePresentAfterSchemaInit() {
        assertTrue(store.findAll().stream().anyMatch(s -> "SEAT-1001".equals(s.getId())), "建表脚本坐席种子应存在");
    }

    @Test
    void seatAgentConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getRouting().setSeatStoreMode("jdbc");
        SeatAgentStore selected = new SeatAgentConfig().seatAgentStore(props,
            singletonProvider(MybatisTestSupport.mapper(dataSource, SeatAgentMapper.class)));
        assertInstanceOf(MybatisSeatAgentStore.class, selected);
    }

    private static <T> ObjectProvider<T> singletonProvider(T bean) {
        return new ObjectProvider<>() {
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
