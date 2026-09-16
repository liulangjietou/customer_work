package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 真 MySQL 与 Spring 事务代理，验证独立受理、并发幂等及过期回执，不修改业务库。 */
class AgentDraftTrialStoreIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static String database;
    private static AnnotationConfigApplicationContext context;
    private static AgentDraftTrialStore store;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate outer;
    private final AgentDraftTrialScope scope = new AgentDraftTrialScope(
        "tenant-a", 7L, UUID.randomUUID().toString(), UUID.randomUUID().toString());

    @BeforeAll
    static void database() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，不能验证真实试用回执");
        }
        database = "draft_trial_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        var source = new DriverManagerDataSource(url(database), USER, PASSWORD);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean("transactionManager", DataSourceTransactionManager.class,
            () -> new DataSourceTransactionManager(source));
        context.register(TransactionConfig.class);
        context.refresh();
        store = context.getBean(AgentDraftTrialStore.class);
        jdbc = new JdbcTemplate(source);
        outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) context.close();
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM ai_agent_draft_trial");
    }

    @Test
    void acceptedReceiptSurvivesCallerRollbackAndRecoversTheSameRequest() {
        outer.executeWithoutResult(transaction -> {
            assertTrue(store.accept(record()));
            transaction.setRollbackOnly();
        });
        assertFalse(store.accept(record()));
        var saved = store.find(scope).orElseThrow();
        assertTrue(saved.matches(new AgentDraftTrialRequest(3L, "查询配送时间")));
        assertEquals(AgentDraftTrialPhase.RUNNING, saved.phase());
        assertEquals(1, store.list(scope.tenantId(), scope.ownerId(), scope.draftId()).size());
    }

    @Test
    void tenantCaseOwnerAndDraftFenceEveryReadAndWrite() {
        assertTrue(store.accept(record()));
        var foreignScopes = List.of(
            new AgentDraftTrialScope("Tenant-A", 7L, scope.draftId(), scope.trialId()),
            new AgentDraftTrialScope("tenant-a", 8L, scope.draftId(), scope.trialId()),
            new AgentDraftTrialScope("tenant-a", 7L, UUID.randomUUID().toString(), scope.trialId()));
        for (var foreign : foreignScopes) {
            assertTrue(store.find(foreign).isEmpty());
            assertTrue(store.list(foreign.tenantId(), foreign.ownerId(), foreign.draftId()).isEmpty());
            assertFalse(store.succeed(foreign, "{}", 1500L));
            assertFalse(store.fail(foreign, "TRIAL_MODEL_FAILED", 1500L));
            store.expire(foreign.tenantId(), foreign.ownerId(), foreign.draftId(), 3000L);
        }
        assertEquals(AgentDraftTrialPhase.RUNNING, store.find(scope).orElseThrow().phase());
    }

    @Test
    void resultAtDeadlineCannotBecomeSuccessAndUnknownCannotBeOverwritten() {
        assertTrue(store.accept(record()));
        assertFalse(store.succeed(scope, "{\"answer\":\"迟到回答\"}", 2000L));
        store.expire(scope.tenantId(), scope.ownerId(), scope.draftId(), 2000L);
        assertEquals(AgentDraftTrialPhase.UNKNOWN, store.find(scope).orElseThrow().phase());
        assertFalse(store.succeed(scope, "{}", 1500L));
        assertFalse(store.fail(scope, "TRIAL_TIMEOUT", 2001L));
    }

    @Test
    void knownTimeoutCanBeRecordedBeforeAnotherTerminalWins() {
        assertTrue(store.accept(record()));
        assertTrue(store.fail(scope, "TRIAL_TIMEOUT", 2001L));
        store.expire(scope.tenantId(), scope.ownerId(), scope.draftId(), 3000L);
        var saved = store.find(scope).orElseThrow();
        assertEquals(AgentDraftTrialPhase.FAILED, saved.phase());
        assertEquals("TRIAL_TIMEOUT", saved.errorCode());
        assertFalse(store.succeed(scope, "{}", 1500L));
    }

    @Test
    void acceptedSuccessIsDurableAndCannotBeChangedByFailureOrExpiry() {
        assertTrue(store.accept(record()));
        assertTrue(store.succeed(scope, "{\"answer\":\"已查询\"}", 1500L));
        assertFalse(store.fail(scope, "TRIAL_MODEL_FAILED", 1501L));
        store.expire(scope.tenantId(), scope.ownerId(), scope.draftId(), 3000L);
        var saved = store.find(scope).orElseThrow();
        assertEquals(AgentDraftTrialPhase.SUCCEEDED, saved.phase());
        assertTrue(saved.resultJson().contains("已查询"));
    }

    @Test
    void invalidJsonIsAWriteFailureAndMustNotBeTreatedAsDuplicateAcceptance() {
        var row = record();
        var invalid = new AgentDraftTrialRecord(scope, row.draftVersion(), row.input(), row.requestFingerprint(),
            row.configurationFingerprint(), "invalid-json", row.phase(), null, null, 1000L, 2000L, null);
        assertThrows(DataAccessException.class, () -> store.accept(invalid));
        assertTrue(store.find(scope).isEmpty());
    }

    @Test
    void simultaneousDuplicateClicksHaveOneExecutionOwner() throws Exception {
        var barrier = new CyclicBarrier(6);
        var executor = Executors.newFixedThreadPool(6);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < 6; index++) {
                results.add(executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return store.accept(record()); }));
            }
            int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_draft_trial", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void upgradingV112PreservesExistingReceiptsAndExactIdentityColumns() throws Exception {
        String upgradeDatabase = "draft_trial_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + upgradeDatabase);
            try {
                var source = new DriverManagerDataSource(url(upgradeDatabase), USER, PASSWORD);
                Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .placeholderReplacement(false).target("112").load().migrate();
                var beforeStore = new AgentDraftTrialStore(source);
                var original = record();
                String id = "a".repeat(36);
                // 构造仅大小写不同的已有标识，证明转换过程中也不会合并主键或丢失记录。
                for (String trialId : List.of(id, id.toUpperCase(java.util.Locale.ROOT))) {
                    var owned = new AgentDraftTrialScope("Tenant-A", 7L, scope.draftId(), trialId);
                    assertTrue(beforeStore.accept(new AgentDraftTrialRecord(owned, 3L, "历史中文问题 😀",
                        original.requestFingerprint(), original.configurationFingerprint(), "{}",
                        AgentDraftTrialPhase.RUNNING, null, null, 1000L, 2000L, null)));
                    assertTrue(beforeStore.succeed(owned, "{\"answer\":\"历史回答\"}", 1500L));
                }
                Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .placeholderReplacement(false).load().migrate();
                var migrated = new JdbcTemplate(source);
                assertEquals(2, migrated.queryForObject("SELECT COUNT(*) FROM ai_agent_draft_trial", Integer.class));
                assertEquals("utf8mb4_unicode_ci", migrated.queryForObject(
                    "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME='ai_agent_draft_trial'",
                    String.class, upgradeDatabase));
                for (String column : List.of("id", "draft_id", "request_fingerprint", "configuration_fingerprint")) {
                    assertEquals("ascii_bin", migrated.queryForObject(
                        "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='ai_agent_draft_trial' AND COLUMN_NAME=?",
                        String.class, upgradeDatabase, column));
                }
                assertEquals("utf8mb4_bin", migrated.queryForObject(
                    "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='ai_agent_draft_trial' AND COLUMN_NAME='tenant_id'",
                    String.class, upgradeDatabase));
                var owned = new AgentDraftTrialScope("Tenant-A", 7L, scope.draftId(), id);
                var saved = beforeStore.find(owned).orElseThrow();
                assertEquals("历史中文问题 😀", saved.input());
                assertEquals(AgentDraftTrialPhase.SUCCEEDED, saved.phase());
                assertTrue(saved.resultJson().contains("历史回答"));
                assertTrue(beforeStore.find(new AgentDraftTrialScope("tenant-a", 7L, scope.draftId(), id)).isEmpty());
            } finally {
                statement.execute("DROP DATABASE " + upgradeDatabase);
            }
        }
    }

    private AgentDraftTrialRecord record() {
        var request = new AgentDraftTrialRequest(3L, "查询配送时间");
        return new AgentDraftTrialRecord(scope, 3L, request.input(),
            AgentDraftTrialRecord.fingerprint(scope, request), "f".repeat(64), "{}",
            AgentDraftTrialPhase.RUNNING, null, null, 1000L, 2000L, null);
    }

    private static String url(String name) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + name
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {
        @Bean
        AgentDraftTrialStore store(DataSource source) {
            return new AgentDraftTrialStore(source);
        }
    }
}
