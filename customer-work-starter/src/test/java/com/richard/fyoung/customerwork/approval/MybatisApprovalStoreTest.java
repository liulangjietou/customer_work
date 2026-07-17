package com.richard.fyoung.customerwork.approval;

import com.richard.fyoung.customerwork.approval.mapper.ApprovalMapper;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 审批工单存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，见 customer-work-schema.sql 中的 cw_approval 表）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue），保证 {@code mvn test} 在无 MySQL 的环境仍然通过；
 * MySQL 在线的机器上真实执行存-取-改-删往返，验证 {@link MybatisApprovalStore}。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisApprovalStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource ds;
    private ApprovalMapper mapper;
    private MybatisApprovalStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        ds = MybatisTestSupport.mysqlDataSource("test-approval-pool");
        MybatisTestSupport.ensureSchema(ds);
        mapper = MybatisTestSupport.mapper(ds, ApprovalMapper.class);
        store = new MybatisApprovalStore(mapper);
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
    void saveFindDelete_shouldRoundTrip() {
        String id = "AP-mybatis-it-" + UUID.randomUUID();
        ApprovalRequest req = new ApprovalRequest(id, ApprovalType.REFUND, "s1", "O1", "299.00", "test", System.currentTimeMillis());
        try {
            store.save(req);

            ApprovalRequest found = store.find(id).orElseThrow();
            assertEquals(id, found.getId());
            assertEquals(ApprovalType.REFUND, found.getType());
            assertEquals(ApprovalStatus.PENDING, found.getStatus());
            assertEquals(ExecutionStatus.NOT_APPLICABLE, found.getExecutionStatus());

            assertTrue(store.findAll().stream().anyMatch(r -> r.getId().equals(id)));
            assertTrue(store.findByStatus(ApprovalStatus.PENDING).stream().anyMatch(r -> r.getId().equals(id)));
        } finally {
            store.delete(id);
        }
        assertFalse(store.find(id).isPresent(), "删除后不应再查到");
    }

    @Test
    void update_shouldPersistDecisionAndExecutionState() {
        String id = "AP-mybatis-it-" + UUID.randomUUID();
        ApprovalRequest req = new ApprovalRequest(id, ApprovalType.REFUND, "s1", "O1", "299.00", "test", System.currentTimeMillis());
        try {
            store.save(req);

            req.approve("alice", null, System.currentTimeMillis());
            req.markExecutionFailed("downstream timeout");
            store.update(req);

            ApprovalRequest reloaded = store.find(id).orElseThrow();
            assertEquals(ApprovalStatus.APPROVED, reloaded.getStatus());
            assertEquals("alice", reloaded.getOperator());
            assertEquals(ExecutionStatus.EXECUTE_FAILED, reloaded.getExecutionStatus());
            assertEquals("downstream timeout", reloaded.getExecutionFailureReason());
            assertEquals(1, reloaded.getExecutionAttempts());

            List<ApprovalRequest> approved = store.findByStatus(ApprovalStatus.APPROVED);
            assertTrue(approved.stream().anyMatch(r -> r.getId().equals(id)));
        } finally {
            store.delete(id);
        }
    }

    @Test
    void approvalConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanApproval().setStoreMode("jdbc");

        ApprovalStore selected = new ApprovalConfig().approvalStore(props, providerOf(mapper));
        assertInstanceOf(MybatisApprovalStore.class, selected, "store-mode=jdbc 应装配 MybatisApprovalStore");
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
