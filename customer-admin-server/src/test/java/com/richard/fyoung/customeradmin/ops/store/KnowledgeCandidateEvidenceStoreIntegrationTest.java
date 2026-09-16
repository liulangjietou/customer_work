package com.richard.fyoung.customeradmin.ops.store;

import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.HASH;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.binding;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.change;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.evaluation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateEvaluation;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真 MySQL 正式迁移与同库事务，验证历史输入、评测证据和父记录的原子提交。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeCandidateEvidenceStoreIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private final String database = "admin_candidate_evidence_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private KnowledgeCandidateBindingStore bindings;
    private KnowledgeCandidateEvaluationStore evaluations;

    @BeforeAll
    void open() throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 1000); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，跳过知识证据集成测试"); }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        }
        source = new HikariDataSource(); source.setJdbcUrl(url(database)); source.setUsername(USER); source.setPassword(PASSWORD);
        source.setMaximumPoolSize(3);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").placeholderReplacement(false).load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        bindings = new KnowledgeCandidateBindingStore(source, new ObjectMapper());
        evaluations = new KnowledgeCandidateEvaluationStore(source, new ObjectMapper());
    }

    @BeforeEach
    void resetOwnedRows() {
        jdbc.update("DELETE FROM ai_knowledge_candidate_revision");
        jdbc.update("DELETE FROM ai_knowledge_candidate");
        jdbc.update("DELETE FROM ai_knowledge_candidate_evaluation");
        jdbc.update("DELETE FROM ai_knowledge_candidate_binding");
        jdbc.update("DELETE FROM ai_agent_improvement_case");
    }

    @AfterAll
    void close() throws Exception {
        if (source != null) source.close();
        if (!created) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void immutableBindingRoundTripsOldAlgorithmVersionsAndPreservesFirstActor() {
        var binding = change(binding("TenantA"), "rubricVersion", "historical-rubric");
        assertEquals(binding, bindings.save(binding, 42));
        assertEquals(binding, bindings.save(binding, 43));
        assertEquals(binding, bindings.find("TenantA", 1, binding.fingerprint()).orElseThrow());
        assertEquals(42L, jdbc.queryForObject("SELECT created_by FROM ai_knowledge_candidate_binding", Long.class));
        assertEquals(1, count("ai_knowledge_candidate_binding"));
        assertTrue(bindings.find("tenanta", 1, binding.fingerprint()).isEmpty());
        assertTrue(bindings.find("TenantA", 2, binding.fingerprint()).isEmpty());
        assertTrue(bindings.find("TenantA", 1, "b".repeat(64)).isEmpty());
        var another = binding("tenanta");
        bindings.save(another, 43);
        assertEquals(another, bindings.find("tenanta", 1, another.fingerprint()).orElseThrow());
    }

    @Test
    void evaluationRoundTripsDerivedRunFieldsAndCannotBeReassignedOrOverwritten() {
        var binding = binding("TenantA");
        var evaluation = evaluation(binding);
        assertEquals(evaluation, evaluations.save(evaluation));
        assertEquals(evaluation, evaluations.save(evaluation));
        assertEquals(evaluation, evaluations.find("TenantA", 1, binding.fingerprint(), evaluation.current().runId()).orElseThrow());
        assertTrue(evaluations.find("tenanta", 1, binding.fingerprint(), evaluation.current().runId()).isEmpty());
        assertTrue(evaluations.find("TenantA", 2, binding.fingerprint(), evaluation.current().runId()).isEmpty());
        assertTrue(evaluations.find("TenantA", 1, "b".repeat(64), evaluation.current().runId()).isEmpty());
        var different = new KnowledgeCandidateEvaluation(evaluation.tenantId(), evaluation.improvementId(), evaluation.artifactFingerprint(),
            evaluation.baseline(), evaluation.current(), evaluation.baselineReplies(), List.of("改写答复", "另一答复"), List.of());
        assertThrows(IllegalStateException.class, () -> evaluations.save(different));
        assertEquals(1, count("ai_knowledge_candidate_evaluation"));
    }

    @Test
    void changedInputOrRawEvaluationJsonIsReportedAsCorruptEvidence() {
        var binding = binding("TenantA");
        var evaluation = evaluation(binding);
        bindings.save(binding, 42); evaluations.save(evaluation);
        jdbc.update("UPDATE ai_knowledge_candidate_binding SET input_json=JSON_SET(input_json,'$.systemPrompt','已改变')");
        assertThrows(IllegalStateException.class, () -> bindings.find("TenantA", 1, binding.fingerprint()));
        jdbc.update("UPDATE ai_knowledge_candidate_evaluation SET evaluation_json=JSON_SET(evaluation_json,'$.candidateReplies',JSON_ARRAY('篡改'))");
        assertThrows(IllegalStateException.class, () -> evaluations.find("TenantA", 1, binding.fingerprint(), evaluation.current().runId()));
    }

    @Test
    void parentWriteFailureRollsBackBothImmutableEvidenceRows() {
        insertParent();
        var binding = binding("TenantA");
        var evaluation = evaluation(binding);
        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
            bindings.save(binding, 42);
            evaluations.save(evaluation);
            jdbc.update("UPDATE ai_agent_improvement_case SET status='INVALID_STATE' WHERE id=1");
        }));
        assertEquals(0, count("ai_knowledge_candidate_binding"));
        assertEquals(0, count("ai_knowledge_candidate_evaluation"));
        assertEquals("OWNED", parentStatus());
    }

    @Test
    void evidenceWriteFailureRollsBackParentReadiness() {
        insertParent();
        var binding = binding("TenantA");
        var evaluation = evaluation(binding);
        jdbc.execute("CREATE TRIGGER reject_knowledge_evaluation BEFORE INSERT ON ai_knowledge_candidate_evaluation "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='evidence storage unavailable'");
        try {
            assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE ai_agent_improvement_case SET status='READY_TO_PUBLISH' WHERE id=1");
                bindings.save(binding, 42);
                evaluations.save(evaluation);
            }));
            assertEquals("OWNED", parentStatus());
            assertEquals(0, count("ai_knowledge_candidate_binding"));
            assertEquals(0, count("ai_knowledge_candidate_evaluation"));
        } finally { jdbc.execute("DROP TRIGGER reject_knowledge_evaluation"); }
    }

    private void insertParent() {
        jdbc.update("INSERT INTO ai_agent_improvement_case(id,tenant_id,source_type,source_key,signal_hash,owner_id,"
                + "sla_due_at_ms,status,next_action_at_ms,created_at_ms,updated_at_ms) VALUES(1,'TenantA','KNOWLEDGE_GAP',?,?,'42',"
                + "1000,'OWNED',1000,100,100)", HASH, HASH);
    }

    @Test
    void publicationIntentAndCandidateReservationRollBackTogetherAndFinishAtomically() {
        insertParent();
        var candidates = new KnowledgeCandidateStore(source);
        var request = new com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest(HASH, 0, 3, "标题", "正文", "关键词");
        transaction.executeWithoutResult(status -> candidates.save("TenantA", "478b7f10-46ba-4217-947f-d86528aa8fbe", request, 42, 100));
        String id = "478b7f10-46ba-4217-947f-d86528aa8fbe";
        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
            candidates.reservePublication("TenantA", id, 1);
            jdbc.update("UPDATE ai_agent_improvement_case SET status='INVALID_STATE'");
        }));
        assertEquals("DRAFT", candidates.find("TenantA", id).orElseThrow().status());
        assertEquals("OWNED", parentStatus());
        transaction.executeWithoutResult(status -> {
            candidates.reservePublication("TenantA", id, 1);
            jdbc.update("UPDATE ai_agent_improvement_case SET status='PUBLISHING',publish_requested_by=42,publish_task_id='task'");
        });
        assertThrows(com.richard.fyoung.customeradmin.common.exception.BizException.class,
            () -> candidates.save("TenantA", id, new com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest(
                HASH, 1, 3, "标题", "另改正文", "关键词"), 43, 101));
        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
            candidates.finishPublication("TenantA", id, 1, true);
            jdbc.update("UPDATE ai_agent_improvement_case SET status='INVALID_STATE'");
        }));
        assertEquals("PUBLISHING", candidates.find("TenantA", id).orElseThrow().status());
        assertEquals("PUBLISHING", parentStatus());
        transaction.executeWithoutResult(status -> {
            candidates.finishPublication("TenantA", id, 1, true);
            jdbc.update("UPDATE ai_agent_improvement_case SET status='PUBLISHED'");
        });
        assertEquals("PUBLISHED", candidates.find("TenantA", id).orElseThrow().status());
        assertEquals("PUBLISHED", parentStatus());
    }

    private String parentStatus() {
        return jdbc.queryForObject("SELECT status FROM ai_agent_improvement_case WHERE id=1", String.class);
    }

    private int count(String table) {
        // 表名只来自本测试的固定字面量。
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
