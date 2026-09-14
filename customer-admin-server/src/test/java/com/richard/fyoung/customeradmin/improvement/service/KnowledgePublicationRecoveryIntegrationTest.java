package com.richard.fyoung.customeradmin.improvement.service;

import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.HASH;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.ID;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.binding;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.change;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.evaluation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.badcase.config.BadcaseGatewayProvider;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.service.EvalAdminService;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementSignalGatewayProvider;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customeradmin.improvement.mapper.AgentImprovementCaseMapper;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeTrialSnapshot;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidatePublishRequest;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateBindingService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateEvaluationService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidatePublicationService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateSnapshotService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeTrialModelService;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateBindingStore;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateEvaluationStore;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateStore;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationStore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实两库与事务代理验证提交裂缝恢复；评分使用已验证的脚本夹具，不代表外部模型质量验收。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgePublicationRecoveryIntegrationTest {
    private static final String TENANT = "TenantA";
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private final List<String> createdDatabases = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private final ImprovementAutomationProperties properties = new ImprovementAutomationProperties();
    private HikariDataSource adminSource;
    private HikariDataSource customerSource;
    private JdbcTemplate admin;
    private JdbcTemplate customer;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private AgentImprovementCaseMapper cases;
    private KnowledgeCandidateStore candidates;
    private KnowledgeCandidateBindingStore bindings;
    private KnowledgeCandidateEvaluationStore evaluations;

    @BeforeAll
    void open() throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 1000); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，跳过两库发布恢复测试"); }
        adminSource = createDatabase("admin_publish_recovery_");
        customerSource = createDatabase("cw_publish_recovery_");
        Flyway.configure().dataSource(adminSource).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        new CustomerWorkSchemaMigrator(customerSource).afterPropertiesSet();
        admin = new JdbcTemplate(adminSource); customer = new JdbcTemplate(customerSource);
        manager = new DataSourceTransactionManager(adminSource); transaction = new TransactionTemplate(manager);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(adminSource);
        factory.setMapperLocations(new ClassPathResource("mapper/AgentImprovementCaseMapper.xml"));
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        cases = new SqlSessionTemplate(factory.getObject()).getMapper(AgentImprovementCaseMapper.class);
        candidates = new KnowledgeCandidateStore(adminSource);
        bindings = new KnowledgeCandidateBindingStore(adminSource, json);
        evaluations = new KnowledgeCandidateEvaluationStore(adminSource, json);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @AfterAll
    void close() throws Exception {
        if (adminSource != null) adminSource.close();
        if (customerSource != null) customerSource.close();
        for (String database : createdDatabases) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
        }
    }

    @Test
    void committedFaqSurvivesAdminRollbackAndFreshWorkerReconcilesTheSameIntent() throws Exception {
        TenantContext.set(TENANT);
        int previousFaqCount = customer.queryForObject("SELECT COUNT(*) FROM cw_knowledge", Integer.class);
        customer.update("INSERT INTO cw_knowledge_gap(tenant_id,scope_id,question_hash,question,first_seen_at_ms,last_seen_at_ms,"
            + "category,classification_origin,review_revision) VALUES(?,?,?,'纸质票如何开具',1,1,'KNOWLEDGE','MANUAL',3)", TENANT, TENANT, HASH);
        var candidate = transaction.execute(status -> candidates.save(TENANT, ID,
            new KnowledgeCandidateSaveRequest(HASH, 0, 3, "纸质开票", "候选开票规则", "开票"), 42, 1));
        var snapshot = new KnowledgeTrialSnapshot(TENANT, ID, candidate.revision(), 3, candidate.contentHash(),
            EvalFingerprint.of("formal-faq-snapshot-v1", "[]"), "[]",
            "[{\"id\":24,\"title\":\"纸质开票\",\"content\":\"候选开票规则\",\"keyword\":\"开票\"}]", 24);
        KnowledgeCandidateBinding frozen = change(binding(TENANT), "knowledge", snapshot);
        var evidence = evaluation(frozen);
        transaction.executeWithoutResult(status -> {
            bindings.save(frozen, 42); evaluations.save(evidence);
            var row = new AgentImprovementCase(); row.setId(1L); row.setTenantId(TENANT);
            row.setSourceType("KNOWLEDGE_GAP"); row.setSourceKey(HASH); row.setSignalHash(HASH);
            row.setOwnerId("42"); row.setSlaDueAtMs(System.currentTimeMillis() + 60000);
            row.setStatus("READY_TO_PUBLISH"); row.setReevaluationStatus("PASSED"); row.setEffectStatus("NOT_STARTED");
            row.setArtifactType(KnowledgeCandidateBinding.ARTIFACT_TYPE); row.setArtifactVersion(frozen.fingerprint());
            row.setAgentId(frozen.agentId()); row.setAgentCode(frozen.agentCode());
            row.setEvalType("QUALITY"); row.setEvalCaseId(frozen.targetCaseId()); row.setEvalRunId(evidence.current().runId());
            row.setCandidateVersionsJson(write(frozen.versions())); row.setNextActionAtMs(Long.MAX_VALUE);
            row.setLeaseUntilMs(0L); row.setCreatedAtMs(1L); row.setUpdatedAtMs(1L); cases.insert(row);
        });
        var firstService = service(false);
        var queued = firstService.publishKnowledgeCandidate(1L,
            new KnowledgeCandidatePublishRequest(frozen.fingerprint(), evidence.current().runId()), 42);
        var firstLease = new ImprovementAutomationLeaseService(cases, properties).claimDue().get(0);
        assertThrows(InjectedAdminRollback.class, () -> transaction.executeWithoutResult(status -> {
            firstService.processAutomation(firstLease);
            assertEquals("PUBLISHED", cases.selectById(1L).getStatus());
            assertEquals(1, count(customer, "cw_knowledge_publication"));
            throw new InjectedAdminRollback();
        }));
        // 客服库的事务已独立提交；后台事务回滚后，父记录和候选仍共同保持原发布意图。
        assertEquals("PUBLISHING", cases.selectById(1L).getStatus());
        assertEquals("PUBLISHING", candidates.find(TENANT, ID).orElseThrow().status());
        assertEquals(queued.publishTaskId(), cases.selectById(1L).getPublishTaskId());
        long faqId = customer.queryForObject("SELECT knowledge_id FROM cw_knowledge_publication", Long.class);
        assertEquals("候选开票规则", customer.queryForObject("SELECT content FROM cw_knowledge WHERE id=?", String.class, faqId));
        assertEquals(42, customer.queryForObject("SELECT requested_by FROM cw_knowledge_publication", Integer.class));
        // 模拟重启后的过期租约和来源漂移；已有回执必须先于新鲜度检查被读取。
        admin.update("UPDATE ai_agent_improvement_case SET lease_until_ms=0 WHERE id=1");
        customer.update("UPDATE cw_knowledge_gap SET category='DEPENDENCY',review_revision=4");
        var worker = new ImprovementAutomationWorker(properties,
            new ImprovementAutomationLeaseService(cases, properties), service(true));
        worker.scanSafely();
        var completed = cases.selectById(1L);
        assertEquals("PUBLISHED", completed.getStatus()); assertEquals("APPLIED", completed.getPublishStatus());
        assertEquals("faq/" + faqId, completed.getPublishRevision());
        assertEquals(queued.publishTaskId(), completed.getPublishTaskId());
        assertEquals("NOT_STARTED", completed.getEffectStatus());
        assertEquals("PUBLISHED", candidates.find(TENANT, ID).orElseThrow().status());
        assertEquals(customer.queryForObject("SELECT published_at_ms FROM cw_knowledge_publication", Long.class), completed.getPublishedAtMs());
        worker.scanSafely();
        assertEquals(1, count(customer, "cw_knowledge")); assertEquals(1, count(customer, "cw_knowledge_publication"));
        assertEquals(1, count(admin, "ai_knowledge_candidate_binding"));
        assertEquals(1, count(admin, "ai_knowledge_candidate_evaluation"));
        assertEquals(previousFaqCount + 1, customer.queryForObject("SELECT COUNT(*) FROM cw_knowledge", Integer.class));
    }

    private ImprovementCaseService service(boolean drifted) {
        var bound = spy(new KnowledgeCandidateBindingService(mock(KnowledgeCandidateService.class),
            mock(KnowledgeCandidateSnapshotService.class), mock(KnowledgeTrialModelService.class),
            mock(EvalDatasetAdminService.class), mock(AiAgentMapper.class), new KnowledgeCandidateBindingStore(adminSource, json)));
        if (drifted) doThrow(new BizException(ResultCode.CONFIG_EDIT_CONFLICT)).when(bound).requireCurrent(any(), any());
        else doNothing().when(bound).requireCurrent(any(), any());
        var provider = mock(OpsGatewayProvider.class); var gateway = mock(OpsGateway.class);
        when(provider.get()).thenReturn(gateway);
        when(gateway.knowledgePublication()).thenReturn(new KnowledgePublicationStore(customerSource, json));
        var evaluationService = new KnowledgeCandidateEvaluationService(bound, mock(KnowledgeTrialModelService.class),
            provider, mock(EvalDatasetAdminService.class), new KnowledgeCandidateEvaluationStore(adminSource, json), json,
            com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.trialRunner());
        var publication = new KnowledgeCandidatePublicationService(bound, evaluationService,
            new KnowledgeCandidateStore(adminSource), provider, json);
        var target = new ImprovementCaseService(cases, mock(ImprovementSignalGatewayProvider.class),
            mock(BadcaseGatewayProvider.class), mock(AiAgentMapper.class), mock(CustomerWorkConfigPublisher.class),
            mock(EvalAdminService.class), mock(EvalDatasetAdminService.class), bound, evaluationService, publication,
            mock(RuntimePublishTaskService.class), mock(RuntimePublishTaskMapper.class), properties, json, manager);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (ImprovementCaseService) proxy.getProxy();
    }

    private HikariDataSource createDatabase(String prefix) throws Exception {
        String database = prefix + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + database
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"); }
        createdDatabases.add(database);
        var source = new HikariDataSource(); source.setJdbcUrl(url(database));
        source.setUsername(USER); source.setPassword(PASSWORD); source.setMaximumPoolSize(4);
        return source;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE BINARY tenant_id=BINARY ?", Integer.class, TENANT);
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }

    private static final class InjectedAdminRollback extends RuntimeException { }
}
