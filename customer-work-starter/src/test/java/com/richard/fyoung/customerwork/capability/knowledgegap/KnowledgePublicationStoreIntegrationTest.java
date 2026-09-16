package com.richard.fyoung.customerwork.capability.knowledgegap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** 正式迁移与自有 MySQL 库，验证真实提交、故障回滚及未经发布存储的并发写入。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgePublicationStoreIntegrationTest {
    private static final String TENANT = "TenantA";
    private static final String HASH = "a".repeat(64);
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private final String database = "cw_faq_publish_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper json = new ObjectMapper();
    private boolean created;
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private KnowledgeMapper mapper;
    private KnowledgePublicationStore store;

    @BeforeAll
    void open() throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 1000); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，跳过发布事务集成测试"); }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        }
        source = new HikariDataSource();
        source.setJdbcUrl(url(database)); source.setUsername(USER); source.setPassword(PASSWORD);
        source.setMaximumPoolSize(5);
        MybatisTestSupport.ensureSchema(source);
        jdbc = new JdbcTemplate(source);
        mapper = MybatisTestSupport.mapper(source, KnowledgeMapper.class);
        store = new KnowledgePublicationStore(source, json);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM cw_knowledge_publication");
        jdbc.update("DELETE FROM cw_knowledge_publication_lock");
        jdbc.update("DELETE FROM cw_knowledge_gap");
        jdbc.update("DELETE FROM cw_knowledge");
        jdbc.update("INSERT INTO cw_knowledge(tenant_id,id,title,content,keyword,source) VALUES(?,11,'旧政策','旧正文','旧关键词','已发布')", TENANT);
        jdbc.update("INSERT INTO cw_knowledge_gap(tenant_id,scope_id,question_hash,question,first_seen_at_ms,last_seen_at_ms,"
            + "category,classification_origin,review_revision) VALUES(?,?,?,'如何开纸质发票',1,1,'KNOWLEDGE','MANUAL',2)", TENANT, TENANT, HASH);
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @AfterAll
    void close() throws Exception {
        if (source != null) source.close();
        if (!created) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
    }

    @Test
    void publishesExactCandidateWithActualIdAndReplaysCommittedReceiptBeforeCheckingDrift() throws Exception {
        // 其他租户消耗主键，证明发布不把 max(本租户)+1 的评测虚拟编号当作真实编号。
        jdbc.update("INSERT INTO cw_knowledge(tenant_id,id,title,content,keyword) VALUES('other',800,'其它租户','其它正文','其它')");
        var command = command("内".repeat(20000));
        assertTrue(store.find(command).isEmpty());
        var receipt = store.publish(command);
        assertTrue(receipt.knowledgeId() > 800);
        assertEquals(command.content(), jdbc.queryForObject("SELECT content FROM cw_knowledge WHERE id=?", String.class, receipt.knowledgeId()));
        assertEquals(command.source(), jdbc.queryForObject("SELECT source FROM cw_knowledge WHERE id=?", String.class, receipt.knowledgeId()));
        assertEquals(42, jdbc.queryForObject("SELECT requested_by FROM cw_knowledge_publication", Integer.class));
        jdbc.update("UPDATE cw_knowledge_gap SET review_revision=3,category='DATA'");
        assertEquals(receipt, new KnowledgePublicationStore(source, json).publish(command));
        assertEquals(receipt, store.find(command).orElseThrow());
        assertEquals(1, count("cw_knowledge_publication"));
        assertEquals(3, count("cw_knowledge"));
    }

    @Test
    void changedTaskPayloadAndSecondTaskForSameRevisionCannotDuplicatePublication() throws Exception {
        var command = command("新正文");
        var receipt = store.publish(command);
        var changed = copy(command, command.taskId(), "篡改正文");
        assertThrows(KnowledgePublicationConflictException.class, () -> store.find(changed));
        assertThrows(KnowledgePublicationConflictException.class, () -> store.publish(changed));
        assertThrows(KnowledgePublicationConflictException.class, () -> store.publish(copy(command, UUID.randomUUID().toString(), command.content())));
        assertEquals(receipt, store.find(command).orElseThrow());
        assertEquals(2, count("cw_knowledge"));
    }

    @Test
    void exactTenantAndSourceAreRequiredWithoutAnyTenantPlugin() throws Exception {
        var command = command("新正文");
        var receipt = store.publish(command);
        TenantContext.set("tenanta");
        assertTrue(store.find(command).isEmpty());
        assertThrows(KnowledgePublicationConflictException.class, () -> store.publish(command));
        TenantContext.clear();
        assertThrows(TenantContextMissingException.class, () -> store.publish(command));
        TenantContext.set(TENANT);
        assertEquals(receipt, store.find(command).orElseThrow());
        assertEquals(2, count("cw_knowledge"));
    }

    @Test
    void changedClassificationRevisionOrMissingSourceCannotPublish() throws Exception {
        var command = command("新正文");
        for (String mutation : new String[] {"classification_origin='RULE'", "category='PENDING'", "review_revision=3"}) {
            jdbc.update("UPDATE cw_knowledge_gap SET category='KNOWLEDGE',classification_origin='MANUAL',review_revision=2");
            jdbc.update("UPDATE cw_knowledge_gap SET " + mutation);
            assertThrows(KnowledgePublicationConflictException.class, () -> store.publish(command));
        }
        jdbc.update("DELETE FROM cw_knowledge_gap");
        assertThrows(KnowledgePublicationConflictException.class, () -> store.publish(command));
        assertEquals(1, count("cw_knowledge"));
        assertEquals(0, count("cw_knowledge_publication"));
    }

    @Test
    void changedFormalCorpusRequiresReevaluationInsteadOfPublishingWithOldProof() throws Exception {
        var command = command("新正文");
        jdbc.update("UPDATE cw_knowledge SET content='已改变'");
        assertThrows(KnowledgePublicationConflictException.class, () -> store.publish(command));
        assertEquals(1, count("cw_knowledge"));
        assertEquals(0, count("cw_knowledge_publication"));
    }

    @Test
    void duplicateFormalTitleIsAConfirmedConflictAndDoesNotLeaveAnUnknownResult() throws Exception {
        jdbc.update("UPDATE cw_knowledge SET title='纸质发票政策'");
        var command = command("新正文");
        assertThrows(KnowledgePublicationConflictException.class, () -> store.publish(command));
        assertEquals(1, count("cw_knowledge"));
        assertEquals(0, count("cw_knowledge_publication"));
    }

    @Test
    void receiptInsertFailureRollsBackFaqAndCanBeRetriedAfterFailureIsRemoved() throws Exception {
        var command = command("😀".repeat(10000));
        jdbc.execute("CREATE TRIGGER fail_knowledge_receipt BEFORE INSERT ON cw_knowledge_publication "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test receipt write failure'");
        try {
            assertThrows(DataAccessException.class, () -> store.publish(command));
            assertEquals(1, count("cw_knowledge"));
            assertEquals(0, count("cw_knowledge_publication"));
            assertTrue(store.find(command).isEmpty());
        } finally { jdbc.execute("DROP TRIGGER fail_knowledge_receipt"); }
        var receipt = store.publish(command);
        assertEquals(command.content(), jdbc.queryForObject("SELECT content FROM cw_knowledge WHERE id=?", String.class, receipt.knowledgeId()));
    }

    @Test
    void concurrentRetriesReturnOneActualReceiptAndFaq() throws Exception {
        var command = command("新正文");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Supplier<KnowledgePublicationReceipt> publish = () -> {
                await(start); return store.publish(command);
            };
            var first = executor.submit(() -> inTenant(publish));
            var second = executor.submit(() -> inTenant(publish));
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(2, count("cw_knowledge"));
            assertEquals(1, count("cw_knowledge_publication"));
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void formalRangeLockBlocksAnUncoordinatedLegacyInsertUntilPublicationCommits(boolean initiallyEmpty) throws Exception {
        if (initiallyEmpty) jdbc.update("DELETE FROM cw_knowledge");
        var command = command("新正文");
        var aboutToInsert = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var pausedStore = new KnowledgePublicationStore(new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.startsWith("INSERT INTO cw_knowledge(")) {
                            aboutToInsert.countDown(); await(releasePublication);
                        }
                        try { return method.invoke(connection, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            }
        }, json);
        try {
            var publish = executor.submit(() -> inTenant(() -> pausedStore.publish(command)));
            assertTrue(aboutToInsert.await(5, TimeUnit.SECONDS));
            var legacy = executor.submit(() -> {
                writerStarted.countDown();
                return jdbc.update("INSERT INTO cw_knowledge(tenant_id,title,content,keyword) VALUES(?,'并发旧入口','并发正文','并发')", TENANT);
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> legacy.get(200, TimeUnit.MILLISECONDS));
            releasePublication.countDown();
            var receipt = publish.get(10, TimeUnit.SECONDS);
            assertEquals(1, legacy.get(10, TimeUnit.SECONDS));
            assertNotEquals(0, receipt.knowledgeId());
            assertEquals(initiallyEmpty ? 2 : 3, count("cw_knowledge"));
            assertEquals(1, count("cw_knowledge_publication"));
        } finally { releasePublication.countDown(); executor.shutdownNow(); }
    }

    private KnowledgePublicationCommand command(String content) throws Exception {
        return new KnowledgePublicationCommand(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), 2,
            "b".repeat(64), UUID.randomUUID().toString(), HASH, 2,
            json.writeValueAsString(mapper.snapshotForTenant(TENANT)), "纸质发票政策", content, "纸质票", 42);
    }

    private KnowledgePublicationCommand copy(KnowledgePublicationCommand old, String taskId, String content) {
        return new KnowledgePublicationCommand(taskId, old.improvementId(), old.candidateId(), old.candidateRevision(),
            old.artifactFingerprint(), old.evaluationRunId(), old.questionHash(), old.sourceReviewRevision(),
            old.baselineCorpusJson(), old.title(), content, old.keyword(), old.requestedBy());
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
