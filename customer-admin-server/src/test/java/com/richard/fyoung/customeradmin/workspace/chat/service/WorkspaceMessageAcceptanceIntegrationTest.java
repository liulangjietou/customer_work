package com.richard.fyoung.customeradmin.workspace.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessagePhase;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatTerminal;
import com.richard.fyoung.customeradmin.workspace.chat.store.WorkspaceMessageReceiptStore;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
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
import reactor.core.publisher.Flux;

/** 正式迁移、自有 MySQL 库与真实受理 SQL；业务流可控，不调用外部模型或执行工具副作用。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceMessageAcceptanceIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "receipt-tenant-a";
    private static final String CLIENT_ID = "478b7f10-46ba-4217-947f-d86528aa8fbe";
    private static final String AGENT = "receipt-agent";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final String database = "admin_receipt_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicInteger executions = new AtomicInteger();
    private boolean created;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private WorkspaceMessageAcceptanceService service;
    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    private org.springframework.jdbc.datasource.DataSourceTransactionManager transactionManager;

    @BeforeAll
    void openDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过工作区受理集成测试");
        }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url(database));
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(8);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var properties = new AdminTenantProperties();
        properties.setEnabled(true);
        context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.registerBean("receiptDataSource", javax.sql.DataSource.class, () -> dataSource,
            definition -> definition.setDestroyMethodName(""));
        transactionManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> transactionManager);
        context.registerBean(WorkspaceMessageReceiptStore.class);
        context.register(TransactionConfiguration.class);
        context.refresh();
        service = new WorkspaceMessageAcceptanceService(context.getBean(WorkspaceMessageReceiptStore.class), properties);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM ai_workspace_message_receipt");
        TenantContext.set(TENANT);
        executions.set(0);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @AfterAll
    void closeDatabase() throws Exception {
        if (context != null) context.close();
        if (dataSource != null) dataSource.close();
        if (!created) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void acceptanceIsCommittedBeforeConstructionAndTerminalIsDurable() {
        Flux<ChatStreamChunk> pending = service.execute(AGENT, 42, "chat", request(), () -> {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_workspace_message_receipt", Integer.class));
            return execution();
        });
        assertEquals(0, executions.get());
        var result = pending.collectList().block(TIMEOUT);
        assertEquals(ChatNodeKind.ACCEPTED, result.get(0).kind());
        assertEquals(CLIENT_ID, result.get(0).receipt().clientMessageId());
        assertEquals(ChatMessagePhase.FINAL, service.receipt(AGENT, "session-1", 42, "chat", CLIENT_ID).terminal().phase());
        assertEquals(1, executions.get());
    }

    @Test
    void surroundingTransactionRollbackCannotUndoAnAlreadyAcceptedRequest() {
        var outer = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            service.execute(AGENT, 42, "chat", request(), this::execution);
            status.setRollbackOnly();
        });
        assertNotNull(service.receipt(AGENT, "session-1", 42, "chat", CLIENT_ID));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_workspace_message_receipt", Integer.class));
        assertEquals(0, executions.get());
    }

    @Test
    void duplicateRequestsAndResubscriptionNeverExecuteAgain() {
        var first = service.execute(AGENT, 42, "chat", request(), this::execution);
        first.collectList().block(TIMEOUT);
        first.collectList().block(TIMEOUT);
        var replay = service.execute(AGENT, 42, "chat", request(), () -> {
            throw new AssertionError("重复请求不能再次构造业务流");
        }).collectList().block(TIMEOUT);
        assertEquals(ChatMessagePhase.FINAL, replay.get(1).terminal().phase());
        assertEquals(1, executions.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_workspace_message_receipt", Integer.class));
    }

    @Test
    void simultaneousDuplicateRequestsHaveOneExecutionWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 6; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    TenantContext.runWith(TENANT, () -> service.execute(AGENT, 42, "chat", request(), this::execution)
                        .collectList().block(TIMEOUT));
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(1, executions.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"message", "raw", "mode", "attachment", "collaboration", "session", "channel", "agent"})
    void reusedIdWithDifferentContentOrResourceConflicts(String change) {
        service.execute(AGENT, 42, "chat", request(), this::execution).collectList().block(TIMEOUT);
        var changed = new ChatRequest(change.equals("session") ? "session-2" : "session-1",
            change.equals("message") ? "其他模型材料" : "模型材料", change.equals("collaboration"),
            change.equals("mode") ? "manual" : "auto", change.equals("attachment") ? List.of("file-2") : List.of("file-1"),
            change.equals("raw") ? "其他原文" : "原文", CLIENT_ID);
        assertThrows(BizException.class, () -> service.execute(change.equals("agent") ? "other-agent" : AGENT,
            42, change.equals("channel") ? "vibecoding" : "chat", changed, this::execution));
        assertEquals(1, executions.get());
    }

    @Test
    void receiptCannotCrossUserTenantSessionAgentOrChannel() {
        service.execute(AGENT, 42, "chat", request(), this::execution).collectList().block(TIMEOUT);
        assertThrows(BizException.class, () -> service.receipt(AGENT, "session-1", 43, "chat", CLIENT_ID));
        assertThrows(BizException.class, () -> TenantContext.callWith("receipt-tenant-b",
            () -> service.receipt(AGENT, "session-1", 42, "chat", CLIENT_ID)));
        assertThrows(BizException.class, () -> service.receipt("other", "session-1", 42, "chat", CLIENT_ID));
        assertThrows(BizException.class, () -> service.receipt(AGENT, "session-2", 42, "chat", CLIENT_ID));
        assertThrows(BizException.class, () -> service.receipt(AGENT, "session-1", 42, "vibecoding", CLIENT_ID));
    }

    @Test
    void caseDistinctTenantsHaveIndependentExecutionRightsForTheSameClientId() {
        service.execute(AGENT, 42, "chat", request(), this::execution).collectList().block(TIMEOUT);
        TenantContext.callWith(TENANT.toUpperCase(java.util.Locale.ROOT), () ->
            service.execute(AGENT, 42, "chat", request(), this::execution).collectList().block(TIMEOUT));
        assertEquals(2, executions.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_workspace_message_receipt", Integer.class));
    }

    @Test
    void databaseAcceptanceFailureDoesNotConstructOrRunTheModel() {
        jdbc.execute("CREATE TRIGGER reject_acceptance BEFORE INSERT ON ai_workspace_message_receipt FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private acceptance failure'");
        try {
            assertThrows(DataAccessException.class, () -> service.execute(AGENT, 42, "chat", request(), () -> {
                throw new AssertionError("受理失败后不能构造模型");
            }));
            assertEquals(0, executions.get());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_workspace_message_receipt", Integer.class));
        } finally { jdbc.execute("DROP TRIGGER reject_acceptance"); }
    }

    @Test
    void cancelAfterAcceptanceLeavesUnknownAndCannotReexecute() {
        var subscription = service.execute(AGENT, 42, "chat", request(), Flux::never).subscribe();
        assertNotNull(service.receipt(AGENT, "session-1", 42, "chat", CLIENT_ID));
        subscription.dispose();
        assertEquals(ChatMessagePhase.UNKNOWN, service.receipt(AGENT, "session-1", 42, "chat", CLIENT_ID).terminal().phase());
        service.execute(AGENT, 42, "chat", request(), this::execution).collectList().block(TIMEOUT);
        assertEquals(0, executions.get());
    }

    @Test
    void unrecordedTerminalIsUnknownAndDoesNotExposeDatabaseFailure() {
        jdbc.execute("CREATE TRIGGER reject_terminal BEFORE UPDATE ON ai_workspace_message_receipt FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private terminal failure'");
        try {
            var output = service.execute(AGENT, 42, "chat", request(), this::execution).collectList().block(TIMEOUT);
            assertEquals(ChatMessagePhase.UNKNOWN, output.get(output.size()-1).terminal().phase());
            assertFalse(output.toString().contains("private terminal failure"));
            assertEquals(null, service.receipt(AGENT, "session-1", 42, "chat", CLIENT_ID).terminal());
        } finally { jdbc.execute("DROP TRIGGER reject_terminal"); }
    }

    private ChatRequest request() {
        return new ChatRequest("session-1", "模型材料", false, "auto", List.of("file-1"), "原文", CLIENT_ID);
    }

    private Flux<ChatStreamChunk> execution() {
        return Flux.defer(() -> {
            executions.incrementAndGet();
            return Flux.just(ChatStreamChunk.terminal(new ChatTerminal("turn-1", "reply-1", ChatMessagePhase.FINAL,
                "stop", true, null, null)));
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
