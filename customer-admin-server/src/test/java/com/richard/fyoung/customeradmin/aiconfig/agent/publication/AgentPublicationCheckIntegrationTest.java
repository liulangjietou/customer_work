package com.richard.fyoung.customeradmin.aiconfig.agent.publication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 真迁移、生产 SQL 解析器与 Spring 只读事务，证明大小写租户边界和聚合快照一致性。 */
class AgentPublicationCheckIntegrationTest {
    private static final long AGENT_ID = 910001L;
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EvalVersionBinding VERSION = new EvalVersionBinding(
        "", "", "model", "prompt", "agent", "", "tool", "", "");
    private static String database;
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private CustomerWorkConfigPublisher publisher;

    @BeforeAll
    static void createDatabase() throws Exception {
        try (var socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 500); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，不能核实发布检查的数据库边界"); }
        database = "publication_check_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        source = new DriverManagerDataSource(url(database), USER, PASSWORD);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
    }

    @BeforeEach
    void setup() throws Exception {
        TenantContext.set("TenantA");
        jdbc.update("DELETE FROM ai_runtime_config_ack");
        jdbc.update("DELETE FROM ai_runtime_publish_task");
        jdbc.update("DELETE FROM ai_channel_binding WHERE agent_id=?", AGENT_ID);
        jdbc.update("DELETE FROM ai_agent WHERE id=?", AGENT_ID);
        jdbc.update("INSERT INTO ai_agent(id,tenant_id,agent_name,agent_code,model_id,status,runtime_revision) "
            + "VALUES (?,'TenantA','发布检查','publication-check',1,1,5)", AGENT_ID);
        channel("own-channel", "TenantA");
        publisher = mock(CustomerWorkConfigPublisher.class);
        when(publisher.isEnabled()).thenReturn(true);
        when(publisher.previewVersionBinding(AGENT_ID)).thenReturn(VERSION);
        task("own-task", "TenantA", "revision-a");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void channelRowsNeedAnExplicitExactTenantEvenWhenTheEntityOmitsTheColumn(boolean plugin) throws Exception {
        channel("case-channel", "tenanta");
        channel("foreign-channel", "other");
        var result = service(plugin).check(AGENT_ID);
        assertEquals(List.of("own-channel"), result.channels().stream().map(AgentPublicationCheck.Channel::code).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLaterForeignTaskCannotReplaceTheOwnedPublication(boolean plugin) throws Exception {
        task("case-task", "tenanta", "revision-case");
        task("foreign-task", "other", "revision-foreign");
        var result = service(plugin).check(AGENT_ID);
        assertEquals("own-task", result.latestPublication().taskId());
        TenantContext.set("tenanta");
        assertThrows(BizException.class, () -> service(plugin).check(AGENT_ID));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acknowledgementsRequireExactTenantRevisionAndContentHash(boolean plugin) throws Exception {
        jdbc.update("UPDATE ai_runtime_publish_task SET ack_targets_json=? WHERE id='own-task'",
            "[\"own\",\"case-tenant\",\"case-revision\",\"case-hash\"]");
        ack("own", "TenantA", "revision-a", "hash-a");
        ack("case-tenant", "tenanta", "revision-a", "hash-a");
        ack("case-revision", "TenantA", "Revision-A", "hash-a");
        ack("case-hash", "TenantA", "revision-a", "Hash-A");
        var result = service(plugin).check(AGENT_ID);
        assertFalse(result.currentRuntimeConfirmed());
        assertEquals(List.of("own"), result.latestPublication().acknowledgements().stream()
            .map(AgentPublicationCheck.Acknowledgement::instanceId).toList());
    }

    @Test
    void aConcurrentTaskUpdateCannotMixSnapshotsWithinOneCheck() throws Exception {
        ack("own", "TenantA", "revision-a", "hash-a");
        when(publisher.previewVersionBinding(AGENT_ID)).thenAnswer(invocation -> {
            // 独立物理连接模拟另一个发布 worker，不加入当前线程的只读事务。
            try (var connection = DriverManager.getConnection(url(database), USER, PASSWORD);
                 var update = connection.prepareStatement("UPDATE ai_runtime_publish_task SET status='FAILED' WHERE id='own-task'")) {
                update.executeUpdate();
            }
            return VERSION;
        });
        var service = service(true);
        var before = service.check(AGENT_ID);
        assertTrue(before.currentRuntimeConfirmed());
        assertEquals("APPLIED", before.latestPublication().status());
        var after = service.check(AGENT_ID);
        assertFalse(after.currentRuntimeConfirmed());
        assertEquals("FAILED", after.latestPublication().status());
    }

    private AgentPublicationCheckService service(boolean tenantPlugin) throws Exception {
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiAgentMapper.class);
        configuration.addMapper(AiChannelBindingMapper.class);
        configuration.addMapper(RuntimePublishTaskMapper.class);
        configuration.addMapper(RuntimeConfigAckMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        if (tenantPlugin) {
            var plugin = new MybatisPlusInterceptor();
            plugin.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of())); factory.setPlugins(plugin);
        }
        var session = new SqlSessionTemplate(factory.getObject());
        var service = new AgentPublicationCheckService(session.getMapper(AiAgentMapper.class),
            session.getMapper(AiChannelBindingMapper.class), session.getMapper(RuntimePublishTaskMapper.class),
            session.getMapper(RuntimeConfigAckMapper.class), publisher, JSON);
        var proxy = new ProxyFactory(service); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (AgentPublicationCheckService) proxy.getProxy();
    }

    private void channel(String code, String tenant) {
        jdbc.update("INSERT INTO ai_channel_binding(channel_code,agent_id,tenant_id,status) VALUES (?,?,?,1)", code, AGENT_ID, tenant);
    }

    private void task(String id, String tenant, String revision) throws Exception {
        jdbc.update("INSERT INTO ai_runtime_publish_task(id,tenant_id,target_id,revision,content_hash,status,"
            + "next_attempt_at_ms,created_at_ms,updated_at_ms,candidate_versions_json,ack_targets_json,gate_status) "
            + "VALUES (?,?,?,?,'hash-a','APPLIED',1,1,1,?,'[\"own\"]','PASSED')",
            id, tenant, AGENT_ID, revision, JSON.writeValueAsString(VERSION));
    }

    private void ack(String instance, String tenant, String revision, String hash) {
        jdbc.update("INSERT INTO ai_runtime_config_ack(tenant_id,revision,content_hash,instance_id,status,"
            + "applied_at_ms,created_at_ms,updated_at_ms) VALUES (?,?,?,?,'APPLIED',1,1,1)", tenant, revision, hash, instance);
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }
}
