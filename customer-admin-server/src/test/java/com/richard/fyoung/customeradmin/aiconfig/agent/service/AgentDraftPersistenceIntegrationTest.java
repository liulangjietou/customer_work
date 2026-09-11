package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentDraftMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** 使用真实迁移和生产租户拦截器验证草稿归属、版本比较以及运行配置修订的原子保护。 */
class AgentDraftPersistenceIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void draftsShouldIsolateOwnersAndTenantsAndRejectStaleVersions() throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 500); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，跳过草稿持久化测试"); }
        String database = "agent_draft_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + database); }
        PooledDataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url(database), USER, PASSWORD);
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .placeholderReplacement(false).load().migrate();
            MybatisConfiguration config = new MybatisConfiguration();
            config.setEnvironment(new Environment("draft-test", new JdbcTransactionFactory(), dataSource));
            config.setMapUnderscoreToCamelCase(true);
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            var interceptors = new MybatisPlusInterceptor();
            interceptors.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of()));
            config.addInterceptor(interceptors);
            config.addMapper(AiAgentDraftMapper.class);
            config.addMapper(AiAgentMapper.class);
            var factory = new MybatisSqlSessionFactoryBuilder().build(config);
            try (var session = factory.openSession(true)) {
                var mapper = session.getMapper(AiAgentDraftMapper.class);
                var service = new AgentDraftService(mapper, mock(AgentService.class), new ObjectMapper());
                String id = UUID.randomUUID().toString();
                TenantContext.set("tenant-a");
                var first = new AgentDraftSaveRequest(0L, null, null, AgentDraftServiceTest.configuration("草稿甲"));
                assertEquals(1, service.save(id, 7L, first).version());
                assertEquals("草稿甲", service.get(id, 7L).configuration().agentName());
                assertNull(service.list(7L).get(0).configuration(), "列表不返回个人配置正文");
                assertTrue(service.list(8L).isEmpty());
                assertThrows(BizException.class, () -> service.get(id, 8L));
                assertThrows(BizException.class, () -> service.delete(id, 8L, 1L));
                assertThrows(BizException.class, () -> service.save(id, 8L,
                    new AgentDraftSaveRequest(1L, null, null, first.configuration())));

                TenantContext.set("tenant-b");
                assertTrue(service.list(7L).isEmpty());
                assertThrows(BizException.class, () -> service.get(id, 7L));
                assertThrows(BizException.class, () -> service.delete(id, 7L, 1L));
                assertThrows(BizException.class, () -> service.save(id, 7L,
                    new AgentDraftSaveRequest(1L, null, null, first.configuration())));
                TenantContext.set("tenant-a");
                assertEquals(2, service.save(id, 7L,
                    new AgentDraftSaveRequest(1L, null, null, AgentDraftServiceTest.configuration("草稿乙"))).version());
                assertThrows(BizException.class, () -> service.save(id, 7L,
                    new AgentDraftSaveRequest(1L, null, null, first.configuration())));
                assertThrows(BizException.class, () -> service.delete(id, 7L, 1L));
                assertEquals("草稿乙", service.get(id, 7L).configuration().agentName());
                service.delete(id, 7L, 2L);
                assertTrue(service.list(7L).isEmpty());

                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO ai_agent(id, tenant_id, agent_name, agent_code, model_id, runtime_revision) "
                        + "VALUES (987654, 'tenant-a', 'Draft target', 'draft-target', 1, 3)");
                }
                var agents = session.getMapper(AiAgentMapper.class);
                TenantContext.set("tenant-b");
                assertEquals(0, agents.claimRevision(987654L, 3L));
                TenantContext.set("tenant-a");
                assertEquals(1, agents.claimRevision(987654L, 3L));
                assertEquals(0, agents.claimRevision(987654L, 3L));
                assertEquals(4L, agents.selectById(987654L).getRuntimeRevision());
                TenantContext.clear();
                assertThrows(Exception.class, () -> service.list(7L), "无租户上下文须拒绝读取");
            }
        } finally {
            TenantContext.clear();
            dataSource.forceCloseAll();
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
        }
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
