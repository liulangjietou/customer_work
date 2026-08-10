package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.handoff.mapper.HandoffMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 人机切换工单存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，表 cw_handoff_ticket 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue），保证 {@code mvn test} 在无 MySQL 的环境仍然通过；
 * MySQL 在线的机器上真实执行存-取-改往返，验证 {@link MybatisHandoffStore}。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisHandoffStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private HandoffMapper mapper;
    private MybatisHandoffStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-handoff-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        mapper = MybatisTestSupport.mapper(dataSource, HandoffMapper.class);
        store = new MybatisHandoffStore(mapper);
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
        String id = "HO-mybatis-it-" + UUID.randomUUID();
        HandoffTicket ticket = new HandoffTicket(id, "s1", "test", System.currentTimeMillis());
        store.save(ticket);

        HandoffTicket found = store.find(id).orElseThrow();
        assertEquals(id, found.getId());
        assertEquals("s1", found.getSessionId());
        assertEquals(HandoffStatus.PENDING, found.getStatus());

        assertTrue(store.findAll().stream().anyMatch(t -> t.getId().equals(id)));
        assertTrue(store.findByStatus(HandoffStatus.PENDING).stream().anyMatch(t -> t.getId().equals(id)));
    }

    @Test
    void update_shouldPersistClaimAndResolveState() {
        String id = "HO-mybatis-it-" + UUID.randomUUID();
        HandoffTicket ticket = new HandoffTicket(id, "s1", "test", System.currentTimeMillis());
        store.save(ticket);

        ticket.claim("alice", System.currentTimeMillis());
        store.update(ticket);

        HandoffTicket claimed = store.find(id).orElseThrow();
        assertEquals(HandoffStatus.CLAIMED, claimed.getStatus());
        assertEquals("alice", claimed.getClaimedBy());

        claimed.resolve("已处理完毕", System.currentTimeMillis());
        store.update(claimed);

        HandoffTicket resolved = store.find(id).orElseThrow();
        assertEquals(HandoffStatus.RESOLVED, resolved.getStatus());
        assertEquals("已处理完毕", resolved.getResolutionNote());

        List<HandoffTicket> resolvedList = store.findByStatus(HandoffStatus.RESOLVED);
        assertTrue(resolvedList.stream().anyMatch(t -> t.getId().equals(id)));
    }

    @Test
    void handoffConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanHandoff().setStoreMode("jdbc");

        HandoffStore selected = new HandoffConfig().handoffStore(props, singletonProvider(mapper));
        assertInstanceOf(MybatisHandoffStore.class, selected, "store-mode=jdbc 应装配 MybatisHandoffStore");
    }

    /** 最小 {@link ObjectProvider}：仅 {@code getObject()} 返回给定 Bean，供 Config 单测在无 Spring 容器下取 Mapper。 */
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
