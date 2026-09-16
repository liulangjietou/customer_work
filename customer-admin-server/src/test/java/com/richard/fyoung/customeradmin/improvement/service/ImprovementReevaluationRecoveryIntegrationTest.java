package com.richard.fyoung.customeradmin.improvement.service;

import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.HASH;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.binding;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.evaluation;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.badcase.config.BadcaseGatewayProvider;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.eval.service.EvalAdminService;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementSignalGatewayProvider;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customeradmin.improvement.mapper.AgentImprovementCaseMapper;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateBindingService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateEvaluationService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidatePublicationService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSourceType;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 自有 MySQL 与真实 Mapper/事务，验证升级、过期租约恢复和迟到完成的持久化边界。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImprovementReevaluationRecoveryIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private final String database = "admin_reeval_recovery_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper json = new ObjectMapper();
    private HikariDataSource source;
    private boolean created;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private DataSourceTransactionManager manager;
    private AgentImprovementCaseMapper mapper;
    private KnowledgeCandidateBindingService bindings;
    private KnowledgeCandidateEvaluationService evaluations;
    private ImprovementCaseService cases;
    private ImprovementAutomationLeaseService leases;

    @BeforeAll
    void open() throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 1000); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，跳过复评恢复集成测试"); }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        }
        source = new HikariDataSource(); source.setJdbcUrl(url(database)); source.setUsername(USER); source.setPassword(PASSWORD);
        source.setMaximumPoolSize(4);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("110")
            .placeholderReplacement(false).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO ai_agent_improvement_case(id,tenant_id,source_type,source_key,signal_hash,owner_id,"
            + "sla_due_at_ms,status,reevaluation_status,next_action_at_ms,created_at_ms,updated_at_ms) "
            + "VALUES(99,'TenantA','KNOWLEDGE_GAP','legacy',?,'42',1000,'REEVALUATING','RUNNING',9223372036854775807,1,1)", HASH);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").placeholderReplacement(false).load().migrate();
        manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/AgentImprovementCaseMapper.xml"));
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AgentImprovementCaseMapper.class);
    }

    @BeforeEach
    void setup() {
        TenantContext.set("TenantA");
        jdbc.update("DELETE FROM ai_agent_improvement_case WHERE id<>99");
        bindings = mock(KnowledgeCandidateBindingService.class);
        evaluations = mock(KnowledgeCandidateEvaluationService.class);
        var properties = new ImprovementAutomationProperties(); properties.setReevaluationTimeoutMs(60000);
        cases = new ImprovementCaseService(mapper, mock(ImprovementSignalGatewayProvider.class),
            mock(BadcaseGatewayProvider.class), mock(AiAgentMapper.class), mock(CustomerWorkConfigPublisher.class),
            mock(EvalAdminService.class), mock(EvalDatasetAdminService.class), bindings, evaluations,
            mock(KnowledgeCandidatePublicationService.class), mock(RuntimePublishTaskService.class),
            mock(RuntimePublishTaskMapper.class), properties, json, manager);
        leases = new ImprovementAutomationLeaseService(mapper, properties);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @AfterAll
    void close() throws Exception {
        if (source != null) source.close();
        if (!created) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void upgradeMakesUnrecoverableLegacyExecutionExplicitAndRequiresIdentityForNewRuns() {
        var legacy = mapper.selectById(99L);
        assertEquals("REEVALUATION_FAILED", legacy.getStatus());
        assertEquals("FAILED", legacy.getReevaluationStatus());
        assertEquals(Long.MAX_VALUE, legacy.getNextActionAtMs());
        assertTrue(legacy.getReevaluationError().contains("重新发起"));
        assertEquals("legacy", legacy.getSourceKey());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE ai_agent_improvement_case SET status='REEVALUATING' WHERE id=99"));
    }

    @Test
    void freshWorkerClaimsOnlyExpiredExecutionAndPersistsRecoverableFailure() throws Exception {
        seed(1, System.currentTimeMillis() - 100);
        seed(2, System.currentTimeMillis() + 60000);
        var claimed = leases.claimDue();
        assertEquals(1, claimed.size()); assertEquals(1L, claimed.get(0).getId());
        assertTrue(leases.claimDue().isEmpty());
        transaction.executeWithoutResult(status -> cases.processAutomation(claimed.get(0)));
        var recovered = mapper.selectById(1L);
        assertEquals("REEVALUATION_FAILED", recovered.getStatus());
        assertEquals("FAILED", recovered.getReevaluationStatus());
        assertEquals(Long.MAX_VALUE, recovered.getNextActionAtMs());
        assertNull(recovered.getLeaseOwner());
        assertEquals("REEVALUATING", mapper.selectById(2L).getStatus());
        verifyNoInteractions(evaluations);
    }

    @Test
    void anOldRecoveryLeaseCannotFailTheReplacementExecution() throws Exception {
        seed(1, System.currentTimeMillis() - 100);
        var claimed = leases.claimDue().get(0);
        String replacement = UUID.randomUUID().toString();
        transaction.executeWithoutResult(status -> {
            var row = mapper.lockById(1L, "TenantA");
            row.failReevaluation(row.getReevaluationAttemptId(), "已超时", System.currentTimeMillis());
            row.beginReevaluation(replacement, System.currentTimeMillis() + 60000, System.currentTimeMillis());
            mapper.updateById(row);
        });
        transaction.executeWithoutResult(status -> cases.processAutomation(claimed));
        var current = mapper.selectById(1L);
        assertEquals("REEVALUATING", current.getStatus());
        assertEquals(replacement, current.getReevaluationAttemptId());
        assertNull(current.getReevaluationError());
        assertTrue(leases.claimDue().isEmpty());
    }

    @Test
    void lateResultUsesTheSavedAttemptIdAndCannotCommitOverANewDatabaseExecution() throws Exception {
        var binding = binding("TenantA");
        var row = seededRow(1, binding);
        mapper.insert(row);
        when(bindings.require(1, binding.fingerprint())).thenReturn(binding);
        var evaluation = evaluation(binding);
        String replacement = UUID.randomUUID().toString();
        when(evaluations.run(eq(binding), eq(HASH), isNull(), anyLong())).thenAnswer(call -> {
            var started = mapper.selectById(1L);
            assertNotNull(started.getReevaluationAttemptId());
            assertEquals(started.getReevaluationDeadlineAtMs(), call.getArgument(3, Long.class));
            transaction.executeWithoutResult(status -> {
                var current = mapper.lockById(1L, "TenantA");
                current.failReevaluation(current.getReevaluationAttemptId(), "已恢复的执行", System.currentTimeMillis());
                current.beginReevaluation(replacement, System.currentTimeMillis() + 60000, System.currentTimeMillis());
                mapper.updateById(current);
            });
            return evaluation;
        });
        assertThrows(BizException.class, () -> cases.reevaluateKnowledgeCandidate(1L, null));
        var current = mapper.selectById(1L);
        assertEquals("REEVALUATING", current.getStatus());
        assertEquals(replacement, current.getReevaluationAttemptId());
        assertNull(current.getEvalRunId());
        assertNull(current.getReevaluationError());
        verify(evaluations, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "tenanta"})
    void detailAndSourceLookupCannotExposeAnotherTenantWithoutSqlPlugin(String caller) throws Exception {
        mapper.insert(seededRow(1, binding("TenantA")));
        TenantContext.set(caller);
        assertAll(
            () -> assertThrows(BizException.class, () -> cases.detail(1L)),
            () -> assertTrue(cases.findBySource(ImprovementSourceType.KNOWLEDGE_GAP, HASH).isEmpty()),
            () -> assertThrows(BizException.class, () -> cases.createEvalCase(1L, null, "99")));
    }

    @Test
    void sourceLookupFindsOwnRecordWhenAnotherTenantHasTheSameHash() throws Exception {
        var foreign = seededRow(1, binding("TenantA"));
        foreign.setTenantId("other"); mapper.insert(foreign);
        var own = seededRow(2, binding("TenantA")); own.setSourceKey(HASH); mapper.insert(own);
        assertEquals(2L, cases.findBySource(ImprovementSourceType.KNOWLEDGE_GAP, HASH).orElseThrow().id());
    }

    private void seed(long id, long deadlineAtMs) throws Exception {
        var row = seededRow(id, binding("TenantA"));
        row.beginReevaluation(UUID.randomUUID().toString(), deadlineAtMs, System.currentTimeMillis());
        mapper.insert(row);
    }

    private AgentImprovementCase seededRow(long id, KnowledgeCandidateBinding binding) throws Exception {
        var row = new AgentImprovementCase();
        row.setId(id); row.setTenantId("TenantA"); row.setSourceType("KNOWLEDGE_GAP");
        row.setSourceKey(id == 1 ? HASH : "source-" + id); row.setSignalHash(HASH); row.setOwnerId("42");
        row.setSourceSignalCount(1L); row.setSlaDueAtMs(System.currentTimeMillis() + 60000);
        row.setStatus("READY_FOR_REEVALUATION"); row.setReevaluationStatus("NOT_RUN"); row.setEffectStatus("NOT_STARTED");
        row.setArtifactType(KnowledgeCandidateBinding.ARTIFACT_TYPE); row.setArtifactVersion(binding.fingerprint());
        row.setCandidateVersionsJson(json.writeValueAsString(binding.versions())); row.setEvalType("QUALITY");
        row.setEvalCaseId(binding.targetCaseId()); row.setAgentId(binding.agentId()); row.setAgentCode(binding.agentCode());
        row.setNextActionAtMs(Long.MAX_VALUE); row.setLeaseUntilMs(0L); row.setCreatedAtMs(1L); row.setUpdatedAtMs(1L);
        return row;
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
