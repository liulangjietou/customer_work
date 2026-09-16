package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.richard.fyoung.customeradmin.badcase.config.BadcaseGatewayProvider;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.service.SensitiveWordService;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 回流与批量词表的真实客服库验收；只创建和清理本用例的独立数据库。 */
class AdminAdoptionBatchAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "adoption-case";
    private static String database;
    private static JdbcTemplate jdbc;
    private static BadcaseGatewayProvider badcases;
    private static ContentGuardGatewayProvider contentGuard;
    private static SensitiveWordService words;

    @BeforeAll
    static void initializeOwnDatabase() throws Exception {
        String candidate = "admin_adoption_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate);
            database = candidate;
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url(database), USER, PASSWORD));
        var tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        var plugins = new AdminCrossDbTenantPlugins(tenant);
        var properties = new CustomerWorkDbProperties();
        properties.setHost(HOST); properties.setPort(PORT); properties.setUsername(USER);
        properties.setPassword(PASSWORD); properties.setDatabase(database);
        contentGuard = new ContentGuardGatewayProvider(properties, plugins);
        contentGuard.get();
        badcases = new BadcaseGatewayProvider(properties, plugins);
        words = new SensitiveWordService(contentGuard);
    }

    @BeforeEach
    void bindTenantAndSeed() {
        TenantContext.set(TENANT);
        jdbc.execute("DROP TRIGGER IF EXISTS reject_adoption_update");
        jdbc.update("DELETE FROM cw_badcase");
        jdbc.update("DELETE FROM cw_knowledge");
        jdbc.update("DELETE FROM cw_eval_case");
        jdbc.update("DELETE FROM cw_sensitive_word");
        for (String id : List.of("own-a", "own-b", "foreign")) {
            jdbc.update("INSERT INTO cw_badcase(id,tenant_id,source,user_input,agent_reply,status,created_at_ms) "
                + "VALUES (?,?,'NEGATIVE_FEEDBACK',?,'待纠正回答','PENDING',1789408800000)",
                id, id.equals("foreign") ? "ADOPTION-CASE" : TENANT, "用户问题-" + id);
        }
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (badcases != null) badcases.close();
        if (contentGuard != null) contentGuard.close();
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void knowledgeAndEvaluationAdoptionPersistReferencesAndRejectForeignTenant() {
        var service = badcases.get();
        var knowledge = service.adoptAsKnowledge("own-a", "验收标题", "正确答案", "验收", "operator");
        var evaluation = service.adoptAsEvalCase("own-a", "case-a", EvalType.INTENT, "consult", "验收", "operator");
        assertEquals(knowledge.getAdoptedKnowledgeId(), evaluation.getAdoptedKnowledgeId());
        assertEquals("case-a", service.find("own-a").orElseThrow().getAdoptedEvalCaseId());
        assertEquals("正确答案", jdbc.queryForObject("SELECT content FROM cw_knowledge WHERE id=?", String.class, knowledge.getAdoptedKnowledgeId()));
        assertEquals("用户问题-own-a", jdbc.queryForObject("SELECT input FROM cw_eval_case WHERE case_id='case-a'", String.class));
        assertThrows(IllegalStateException.class, () -> service.adoptAsKnowledge("foreign", "不得写入", "越权", "foreign", "operator"));
        assertThrows(IllegalStateException.class, () -> service.ignore("own-a", "已采纳不得忽略", "operator"));
        service.ignore("own-b", "误触", "operator");
        assertEquals("IGNORED", jdbc.queryForObject("SELECT status FROM cw_badcase WHERE id='own-b'", String.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM cw_badcase WHERE id='foreign'", String.class));
    }

    @Test
    void repeatedKnowledgeAdoptionMustNotLeaveAnUnreferencedEntry() {
        var service = badcases.get();
        service.adoptAsKnowledge("own-a", "第一次", "答案", "验收", "operator");
        assertThrows(IllegalStateException.class, () -> service.adoptAsKnowledge("own-a", "第二次", "答案", "验收", "operator"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_knowledge WHERE source='badcase:own-a'", Integer.class));
    }

    @Test
    void repeatedEvaluationAdoptionMustNotLeaveAnotherEvaluationCase() {
        var service = badcases.get();
        service.adoptAsEvalCase("own-a", "case-first", EvalType.INTENT, "consult", "验收", "operator");
        assertThrows(IllegalStateException.class, () -> service.adoptAsEvalCase("own-a", "case-second", EvalType.INTENT, "consult", "验收", "operator"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case WHERE origin_ref='own-a'", Integer.class));
    }

    @Test
    void concurrentKnowledgeAdoptionsMustPersistOnlyOneTarget() throws Exception {
        var service = badcases.get();
        var results = concurrentOnSameBadcase(
            () -> service.adoptAsKnowledge("own-a", "并发甲", "答案甲", "验收", "operator-a"),
            () -> service.adoptAsKnowledge("own-a", "并发乙", "答案乙", "验收", "operator-b"));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_knowledge WHERE source='badcase:own-a'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_knowledge k JOIN cw_badcase b "
            + "ON k.id=b.adopted_knowledge_id WHERE b.id='own-a'", Integer.class));
    }

    @Test
    void concurrentEvaluationAdoptionsMustPersistOnlyOneTarget() throws Exception {
        var service = badcases.get();
        var results = concurrentOnSameBadcase(
            () -> service.adoptAsEvalCase("own-a", "concurrent-a", EvalType.INTENT, "consult", "验收", "operator-a"),
            () -> service.adoptAsEvalCase("own-a", "concurrent-b", EvalType.INTENT, "refund", "验收", "operator-b"));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case WHERE origin_ref='own-a'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case e JOIN cw_badcase b "
            + "ON e.case_id=b.adopted_eval_case_id WHERE b.id='own-a'", Integer.class));
    }

    @Test
    void concurrentKnowledgeAndEvaluationAdoptionMustPreserveBothReferences() throws Exception {
        var service = badcases.get();
        assertEquals(List.of(true, true), concurrentOnSameBadcase(
            () -> service.adoptAsKnowledge("own-a", "知识采纳", "答案", "验收", "operator-a"),
            () -> service.adoptAsEvalCase("own-a", "concurrent-eval", EvalType.INTENT, "consult", "验收", "operator-b")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_badcase WHERE id='own-a' "
            + "AND adopted_knowledge_id IS NOT NULL AND adopted_eval_case_id='concurrent-eval'", Integer.class));
    }

    @Test
    void differentBadcasesCannotOverwriteTheSameEvaluationCaseConcurrently() throws Exception {
        var service = badcases.get();
        var workers = Executors.newFixedThreadPool(2);
        String mutex = "eval-create-" + UUID.randomUUID();
        try (var owner = DriverManager.getConnection(url(database), USER, PASSWORD)) {
            try (var statement = owner.createStatement();
                 var result = statement.executeQuery("SELECT GET_LOCK('" + mutex + "',0)")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            // 在两个真实 INSERT 到达后释放门闩，稳定复现先查编号、再 upsert 的竞争。
            jdbc.execute("CREATE TRIGGER pause_eval_creation BEFORE INSERT ON cw_eval_case FOR EACH ROW "
                + "BEGIN SET @cw_eval_gate=GET_LOCK('" + mutex + "',20); "
                + "DO RELEASE_LOCK('" + mutex + "'); END");
            var first = workers.submit(() -> executeAsTenant(() -> service.adoptAsEvalCase(
                "own-a", "shared-case", EvalType.INTENT, "consult", "验收", "operator-a")));
            var second = workers.submit(() -> executeAsTenant(() -> service.adoptAsEvalCase(
                "own-b", "shared-case", EvalType.INTENT, "refund", "验收", "operator-b")));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                int waiting;
                do {
                    waiting = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                        + "WHERE DB=? AND STATE='User lock'", Integer.class, database);
                    if (waiting >= 2) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                assertEquals(2, waiting, "两个请求必须实际到达评测用例创建入口");
            } finally {
                try (var statement = owner.createStatement()) {
                    statement.execute("DO RELEASE_LOCK('" + mutex + "')");
                }
            }
            assertEquals(1, List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                .stream().filter(Boolean::booleanValue).count());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_badcase "
                + "WHERE adopted_eval_case_id='shared-case'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case e JOIN cw_badcase b "
                + "ON b.id=e.origin_ref AND b.adopted_eval_case_id=e.case_id "
                + "WHERE e.case_id='shared-case' AND e.input=b.user_input", Integer.class));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(25, TimeUnit.SECONDS));
            jdbc.execute("DROP TRIGGER IF EXISTS pause_eval_creation");
        }
    }

    /** 用独立连接扣住原记录，观察两个真实事务均在等待后才释放，不依赖线程启动时序。 */
    private List<Boolean> concurrentOnSameBadcase(Callable<?> first, Callable<?> second) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try (var lock = DriverManager.getConnection(url(database), USER, PASSWORD)) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement();
                 var rows = statement.executeQuery("SELECT id FROM cw_badcase WHERE id='own-a' FOR UPDATE")) {
                assertTrue(rows.next());
            }
            var firstResult = workers.submit(() -> executeAsTenant(first));
            var secondResult = workers.submit(() -> executeAsTenant(second));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                int waiting;
                do {
                    waiting = jdbc.queryForObject("SELECT COUNT(DISTINCT requested.ENGINE_TRANSACTION_ID) "
                        + "FROM performance_schema.data_lock_waits waits JOIN performance_schema.data_locks requested "
                        + "ON requested.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID "
                        + "WHERE requested.OBJECT_SCHEMA=? AND requested.OBJECT_NAME='cw_badcase'", Integer.class, database);
                    if (waiting >= 2) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                assertEquals(2, waiting, "两个操作必须实际进入同一 Badcase 的锁等待");
            } finally {
                lock.commit();
            }
            return List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private boolean executeAsTenant(Callable<?> action) throws Exception {
        TenantContext.set(TENANT);
        try {
            action.call();
            return true;
        } catch (IllegalStateException rejected) {
            assertTrue(rejected.getMessage().contains("already adopted")
                || rejected.getMessage().contains("eval case id already exists"),
                () -> "Unexpected failure during concurrent adoption: " + rejected);
            return false;
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void failedBadcaseStateWriteMustRollBackTheKnowledgeEntry() {
        jdbc.execute("CREATE TRIGGER reject_adoption_update BEFORE UPDATE ON cw_badcase FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='acceptance write failure'");
        assertThrows(RuntimeException.class, () -> badcases.get().adoptAsKnowledge("own-a", "故障写入", "答案", "验收", "operator"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM cw_badcase WHERE id='own-a'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cw_knowledge WHERE source='badcase:own-a'", Integer.class));
    }

    @Test
    void sensitiveWordImportAndExportRoundTripPreservesCategoriesActionsAndOtherTenants() {
        jdbc.update("INSERT INTO cw_sensitive_word(tenant_id,word,category,action,enabled) VALUES ('ADOPTION-CASE','外租户词','CUSTOM','REVIEW',1)");
        assertEquals(2, words.importWords(List.of("测试词甲", "测试词乙,COMPETITOR,MASK", "  ")));
        var exported = words.exportWords();
        assertEquals(List.of("测试词乙,COMPETITOR,MASK", "测试词甲,CUSTOM,BLOCK"), exported.stream().sorted().toList());
        assertEquals(2, words.importWords(exported));
        assertEquals(2, words.exportWords().size());
        assertEquals("REVIEW", jdbc.queryForObject("SELECT action FROM cw_sensitive_word WHERE word='外租户词'", String.class));
        assertTrue(words.exportWords().stream().noneMatch(line -> line.contains("外租户词")));
    }

    @Test
    void sameWordAcrossCaseDistinctTenantsMustNotOverwriteTheOtherTenant() {
        jdbc.update("INSERT INTO cw_sensitive_word(tenant_id,word,category,action,enabled) VALUES ('ADOPTION-CASE','同名词','CUSTOM','REVIEW',1)");
        assertEquals(List.of("ADOPTION-CASE:REVIEW"), jdbc.queryForList(
            "SELECT CONCAT(tenant_id,':',action) FROM cw_sensitive_word WHERE word='同名词'", String.class));
        words.importWords(List.of("同名词,CUSTOM,BLOCK"));
        assertEquals(List.of("ADOPTION-CASE:REVIEW"), jdbc.queryForList(
            "SELECT CONCAT(tenant_id,':',action) FROM cw_sensitive_word "
                + "WHERE CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND word='同名词'", String.class, "ADOPTION-CASE"),
            () -> "Existing rows after import: " + jdbc.queryForList(
                "SELECT tenant_id,word,action FROM cw_sensitive_word WHERE word='同名词'"));
        assertEquals(List.of("同名词,CUSTOM,BLOCK"), words.exportWords());
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
