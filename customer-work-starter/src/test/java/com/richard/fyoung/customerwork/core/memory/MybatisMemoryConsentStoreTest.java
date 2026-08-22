package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.MemoryConsentMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用生产租户拦截器验证长期记忆同意的幂等写入与跨租户隔离。 */
class MybatisMemoryConsentStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisMemoryConsentStore store;
    private String subjectId;
    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过长期记忆同意持久化测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-memory-consent-pool");
        MybatisTestSupport.ensureSchema(dataSource);

        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        SqlSessionFactory factory = new CustomerWorkPersistenceConfig()
            .customerWorkSqlSessionFactory(dataSource, properties);
        MemoryConsentMapper mapper = new SqlSessionTemplate(factory).getMapper(MemoryConsentMapper.class);
        store = new MybatisMemoryConsentStore(mapper);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        subjectId = "user-" + suffix;
        tenantA = "memory-a-" + suffix;
        tenantB = "memory-b-" + suffix;
    }

    @AfterEach
    void tearDown() throws Exception {
        TenantContext.clear();
        if (dataSource != null && subjectId != null) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM cw_memory_consent WHERE subject_id = ?")) {
                statement.setString(1, subjectId);
                statement.executeUpdate();
            } finally {
                dataSource.close();
            }
        }
    }

    @Test
    void saveAndFind_shouldUpsertAndIsolateByTenant() throws Exception {
        MemorySubjectKey subjectA = subject(tenantA);
        MemorySubjectKey subjectB = subject(tenantB);
        long now = System.currentTimeMillis();

        TenantContext.runWith(tenantA, () -> {
            store.save(consent(subjectA, MemoryConsentStatus.GRANTED, now));
            store.save(consent(subjectA, MemoryConsentStatus.WITHDRAWN, now + 1));
        });
        TenantContext.runWith(tenantB,
            () -> store.save(consent(subjectB, MemoryConsentStatus.GRANTED, now + 2)));

        assertEquals(MemoryConsentStatus.WITHDRAWN,
            TenantContext.callWith(tenantA, () -> store.find(subjectA).orElseThrow()).status());
        assertEquals(MemoryConsentStatus.GRANTED,
            TenantContext.callWith(tenantB, () -> store.find(subjectB).orElseThrow()).status());
        assertEquals(2, rowCount(), "相同主体 ID 在两个租户应各保留一行，单租户重复写应幂等更新");
    }

    @Test
    void access_shouldFailClosedWithoutMatchingTenantContext() {
        MemorySubjectKey subject = subject(tenantA);

        assertThrows(TenantContextMissingException.class, () -> store.find(subject));
        assertThrows(IllegalArgumentException.class,
            () -> TenantContext.runWith(tenantB, () -> store.find(subject)));
    }

    private MemorySubjectKey subject(String tenantId) {
        return new MemorySubjectKey(tenantId, MemorySubjectType.USER, subjectId,
            MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);
    }

    private MemoryConsent consent(MemorySubjectKey subject, MemoryConsentStatus status, long now) {
        Long withdrawnAt = status == MemoryConsentStatus.WITHDRAWN ? now : null;
        return new MemoryConsent(subject, status, "privacy-v1", now, withdrawnAt, now);
    }

    private int rowCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM cw_memory_consent WHERE subject_id = ?")) {
            statement.setString(1, subjectId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
