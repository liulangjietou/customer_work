package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.entity.FactLogDO;
import com.richard.fyoung.customerwork.core.memory.entity.LongTermMemoryDO;
import com.richard.fyoung.customerwork.core.memory.entity.MemoryConsentDO;
import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.MemoryConsentMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 MySQL 验证三张记忆治理表的跨租户分批保留策略 SQL。 */
class MemoryRetentionIntegrationTest {

    private HikariDataSource dataSource;
    private LongTermMemoryMapper longTermMapper;
    private FactLogMapper factLogMapper;
    private MemoryConsentMapper consentMapper;
    private String marker;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过长期记忆保留策略集成测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-memory-retention-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        SqlSessionFactory factory = new CustomerWorkPersistenceConfig()
            .customerWorkSqlSessionFactory(dataSource, properties);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        longTermMapper = template.getMapper(LongTermMemoryMapper.class);
        factLogMapper = template.getMapper(FactLogMapper.class);
        consentMapper = template.getMapper(MemoryConsentMapper.class);
        marker = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @AfterEach
    void tearDown() throws Exception {
        TenantContext.clear();
        if (dataSource != null && marker != null) {
            deleteMarked("cw_long_term_memory");
            deleteMarked("cw_fact_log");
            deleteMarked("cw_memory_consent");
            dataSource.close();
        }
    }

    @Test
    void cleanup_shouldDeleteOnlyExpiredRowsAcrossTenants() {
        String oldScope = "retention-old-" + marker;
        String recentScope = "retention-recent-" + marker;
        long oldTimestamp = -Duration.ofDays(2).toMillis();
        long recentTimestamp = 0L;
        insertRows("retention-a-" + marker, oldScope, oldTimestamp, MemoryConsentStatus.WITHDRAWN);
        insertRows("retention-b-" + marker, recentScope, recentTimestamp, MemoryConsentStatus.GRANTED);

        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getMemory().setRetentionDays(1);
        properties.getMemory().setWithdrawnConsentRetentionDays(1);
        properties.getMemory().setRetentionCleanupBatchSize(100);
        MemoryRetentionService service = new MemoryRetentionService(properties,
            provider(longTermMapper), provider(factLogMapper), provider(consentMapper));

        MemoryRetentionService.CleanupResult result = service.cleanup(0L);

        assertEquals(1, result.longTermDeleted());
        assertEquals(1, result.factLogDeleted());
        assertEquals(1, result.consentDeleted());
        assertEquals(0, countByScope("cw_long_term_memory", oldScope));
        assertEquals(0, countByScope("cw_fact_log", oldScope));
        assertEquals(0, countByScope("cw_memory_consent", oldScope));
        assertEquals(1, countByScope("cw_long_term_memory", recentScope));
        assertEquals(1, countByScope("cw_fact_log", recentScope));
        assertEquals(1, countByScope("cw_memory_consent", recentScope),
            "有效授权即使时间较早也不应由撤回记录清理 SQL 删除");
    }

    private void insertRows(String tenantId, String scopeId, long timestamp, MemoryConsentStatus status) {
        TenantContext.runWith(tenantId, () -> {
            LongTermMemoryDO memory = new LongTermMemoryDO();
            memory.setScopeId(scopeId);
            memory.setFact("fact-" + marker);
            memory.setScopeHash((marker + "0000000000000000000000000000000000000000000000000000").substring(0, 64));
            memory.setCreatedAtMs(timestamp);
            longTermMapper.insertIfAbsent(memory);

            FactLogDO fact = new FactLogDO();
            fact.setScopeId(scopeId);
            fact.setFact("fact-" + marker);
            fact.setTs(timestamp);
            factLogMapper.insert(fact);

            MemoryConsentDO consent = new MemoryConsentDO();
            consent.setSubjectType(MemorySubjectType.USER.name());
            consent.setSubjectId("subject-" + marker);
            consent.setAgentId(MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);
            consent.setScopeId(scopeId);
            consent.setStatus(status.name());
            consent.setConsentVersion("privacy-v1");
            consent.setGrantedAtMs(timestamp);
            consent.setWithdrawnAtMs(status == MemoryConsentStatus.WITHDRAWN ? timestamp : null);
            consent.setUpdatedAtMs(timestamp);
            consentMapper.upsert(consent);
        });
    }

    private int countByScope(String table, String scopeId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM `" + table + "` WHERE scope_id = ?")) {
            statement.setString(1, scopeId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to count retention test rows", e);
        }
    }

    private void deleteMarked(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM `" + table + "` WHERE scope_id LIKE ?")) {
            statement.setString(1, "%" + marker);
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
