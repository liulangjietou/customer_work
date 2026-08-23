package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.capability.semanticcache.mapper.SemanticCacheMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用生产租户拦截器验证 JDBC 语义缓存的租户级严格失效。 */
class MybatisSemanticCacheStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisSemanticCacheStore store;
    private String marker;
    private String tenantA;
    private String tenantB;

    private void setUpDatabase() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过语义缓存 JDBC 租户失效测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-semantic-cache-pool");
        MybatisTestSupport.ensureSchema(dataSource);

        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        SqlSessionFactory factory = new CustomerWorkPersistenceConfig()
            .customerWorkSqlSessionFactory(dataSource, properties);
        SemanticCacheMapper mapper = new SqlSessionTemplate(factory).getMapper(SemanticCacheMapper.class);
        store = new MybatisSemanticCacheStore(mapper);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        marker = "runtime-invalidation-" + suffix;
        tenantA = "cache-a-" + suffix;
        tenantB = "cache-b-" + suffix;
    }

    @AfterEach
    void tearDown() throws Exception {
        TenantContext.clear();
        if (dataSource != null && marker != null) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM cw_semantic_cache WHERE question = ?")) {
                statement.setString(1, marker);
                statement.executeUpdate();
            } finally {
                dataSource.close();
            }
        }
    }

    @Test
    void clearCurrentTenant_shouldDeleteOnlyRowsVisibleThroughTenantInterceptor() throws Exception {
        setUpDatabase();
        TenantContext.runWith(tenantA, () -> store.save(entry("user-a", "answer-a")));
        TenantContext.runWith(tenantB, () -> store.save(entry("user-b", "answer-b")));
        assertEquals(2, rowCount(), "失效前两个租户应各有一条同批测试数据");

        int removed = TenantContext.callWith(tenantA, store::clearCurrentTenant);

        assertEquals(1, removed);
        assertEquals(1, rowCount());
        assertEquals(0L, TenantContext.callWith(tenantA, () -> store.count("user-a")));
        assertEquals(1L, TenantContext.callWith(tenantB, () -> store.count("user-b")),
            "DELETE 必须由 TenantLineInnerInterceptor 补入 tenant_id，不能误清其他租户");
    }

    @Test
    void clearCurrentTenant_shouldPropagateStorageFailure() {
        SemanticCacheMapper mapper = mock(SemanticCacheMapper.class);
        when(mapper.deleteCurrentTenant()).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
            () -> new MybatisSemanticCacheStore(mapper).clearCurrentTenant(),
            "严格失效失败必须上抛，以便 Nacos 拒绝切换新配置");
    }

    private SemanticCacheEntry entry(String scopeId, String answer) {
        long now = System.currentTimeMillis();
        return SemanticCacheEntry.of(scopeId, "consult", marker, "1.0,0.0", answer, now);
    }

    private int rowCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM cw_semantic_cache WHERE question = ?")) {
            statement.setString(1, marker);
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
