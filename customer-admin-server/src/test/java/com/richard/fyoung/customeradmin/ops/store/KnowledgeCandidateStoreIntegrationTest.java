package com.richard.fyoung.customeradmin.ops.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 正式迁移与真实事务验证候选隔离、不可变修订和失败原子性，不调用模型或修改共享业务库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeCandidateStoreIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String ID = "5b260032-7451-4924-b94b-2e345458a2d1";
    private static final String HASH = "a".repeat(64);
    private final String database = "admin_candidate_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;
    private HikariDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private KnowledgeCandidateStore store;

    @BeforeAll
    void open() throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 1000); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，跳过候选集成测试"); }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url(database));
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(4);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("candidateDataSource", javax.sql.DataSource.class, () -> dataSource,
            definition -> definition.setDestroyMethodName(""));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(KnowledgeCandidateStore.class);
        context.register(Transactions.class);
        context.refresh();
        store = context.getBean(KnowledgeCandidateStore.class);
    }

    @BeforeEach
    void clearOwnedRows() {
        jdbc.update("DELETE FROM ai_knowledge_candidate_revision");
        jdbc.update("DELETE FROM ai_knowledge_candidate");
    }

    @AfterAll
    void close() throws Exception {
        if (context != null) context.close();
        if (dataSource != null) dataSource.close();
        if (!created) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
    }

    @Test
    void savesDraftAndPreservesThePreviousImmutableRevision() {
        store.save("TenantA", ID, request(0, "初稿"), 42, 100);
        var next = store.save("TenantA", ID, request(1, "修订正文"), 43, 200);
        assertEquals(2, next.revision());
        assertEquals("DRAFT", next.status());
        assertEquals("修订正文", store.bySource("TenantA", HASH).orElseThrow().content());
        assertEquals(List.of("初稿", "修订正文"), jdbc.queryForList(
            "SELECT content FROM ai_knowledge_candidate_revision ORDER BY revision", String.class));
        assertEquals(43, next.editedBy());
    }

    @Test
    void chineseAndEmojiAtTheApiLimitFitTheFormalFaqColumnTypes() {
        long revision = 0;
        for (String content : List.of("内".repeat(20000), "😀".repeat(10000))) {
            var request = new KnowledgeCandidateSaveRequest(HASH, revision++, 1,
                "标".repeat(200), content, "词".repeat(255));
            var saved = store.save("TenantA", ID, request, 42, 100);
            assertEquals(content, store.find("TenantA", ID).orElseThrow().content());
            assertEquals(request.keyword(), saved.keyword());
        }
        assertEquals(List.of(60000, 40000), jdbc.queryForList(
            "SELECT OCTET_LENGTH(content) FROM ai_knowledge_candidate_revision ORDER BY revision", Integer.class));
        assertEquals("text", jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.columns "
            + "WHERE table_schema=? AND table_name='ai_knowledge_candidate_revision' AND column_name='content'",
            String.class, database));
    }

    @Test
    void responseLossRetryReturnsTheSavedRevisionWithoutDuplicateAuditRows() {
        var first = store.save("TenantA", ID, request(0, "初稿"), 42, 100);
        assertEquals(first, store.save("TenantA", ID, request(0, "初稿"), 43, 200));
        var second = store.save("TenantA", ID, request(1, "修订正文"), 43, 200);
        assertEquals(second, store.save("TenantA", ID, request(1, "修订正文"), 44, 300));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_candidate_revision", Integer.class));
    }

    @Test
    void caseDistinctTenantsCanUseTheSameIdWithoutReadingEachOther() {
        store.save("TenantA", ID, request(0, "甲租户"), 42, 100);
        assertTrue(store.find("tenanta", ID).isEmpty());
        store.save("tenanta", ID, request(0, "乙租户"), 42, 100);
        assertEquals("甲租户", store.find("TenantA", ID).orElseThrow().content());
        assertEquals("乙租户", store.find("tenanta", ID).orElseThrow().content());
    }

    @Test
    void staleContentAndChangingTheSourceCannotOverwriteTheCandidate() {
        store.save("TenantA", ID, request(0, "初稿"), 42, 100);
        assertThrows(BizException.class, () -> store.save("TenantA", ID, request(0, "另一个初稿"), 43, 200));
        assertThrows(BizException.class, () -> store.save("TenantA", ID,
            new KnowledgeCandidateSaveRequest("b".repeat(64), 1, 1, "标题", "新来源", "关键词"), 43, 200));
        assertEquals("初稿", store.find("TenantA", ID).orElseThrow().content());
    }

    @Test
    void concurrentEditsHaveOnlyOneWinnerAndNeverSilentlyOverwrite() throws Exception {
        store.save("TenantA", ID, request(0, "初稿"), 42, 100);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.<java.util.concurrent.Callable<Boolean>>of(
                () -> edit("并发甲"), () -> edit("并发乙"));
            int successes = 0;
            for (var future : pool.invokeAll(tasks)) if (future.get()) successes++;
            assertEquals(1, successes);
            assertEquals(2, store.find("TenantA", ID).orElseThrow().revision());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void failedRevisionInsertRollsBackNewParentAndExistingParentAdvance() {
        store.save("TenantA", ID, request(0, "初稿"), 42, 100);
        jdbc.execute("CREATE TRIGGER reject_candidate_revision BEFORE INSERT ON ai_knowledge_candidate_revision "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='candidate write failure'");
        try {
            assertThrows(DataAccessException.class, () -> store.save("TenantA", ID, request(1, "失败修订"), 43, 200));
            assertThrows(DataAccessException.class, () -> store.save("TenantB", ID, request(0, "失败新建"), 43, 200));
            assertEquals(1, store.find("TenantA", ID).orElseThrow().revision());
            assertTrue(store.find("TenantB", ID).isEmpty());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_candidate", Integer.class));
        } finally { jdbc.execute("DROP TRIGGER reject_candidate_revision"); }
    }

    private boolean edit(String content) {
        try { store.save("TenantA", ID, request(1, content), 43, 200); return true; }
        catch (BizException conflict) { return false; }
    }

    private KnowledgeCandidateSaveRequest request(long revision, String content) {
        return new KnowledgeCandidateSaveRequest(HASH, revision, 1, "标题", content, "关键词");
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }
}
