package com.richard.fyoung.customerworkapp.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.MybatisChatMessageStore;
import com.richard.fyoung.customerwork.data.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.data.outbox.MybatisOutboxStore;
import com.richard.fyoung.customerwork.data.outbox.OutboxService;
import com.richard.fyoung.customerwork.data.outbox.mapper.OutboxMessageMapper;
import com.richard.fyoung.customerwork.data.ticket.MybatisTicketStore;
import com.richard.fyoung.customerwork.data.ticket.OutboxTicketEventPublisher;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.infra.config.properties.OutboxProperties;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用隔离 MySQL 库、生产租户插件及真实事务验证受理；故意不依赖实例内锁证明跨实例竞争。 */
class ChatMessageAcceptanceIntegrationTest {
    private static final String ROOT_URL = "jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DATABASE = "cw_chat_accept_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String SESSION = "uU1:acceptance-it";
    private static HikariDataSource dataSource;
    private static SqlSessionTemplate sql;
    private static CustomerWorkTransactionExecutor transactions;
    private MybatisChatMessageStore store;
    private ChatLogService chatLog;
    private TicketService tickets;
    private ChatTurnService turns;
    private SubjectQuotaGuard quota;
    private WsSessionRegistry registry;

    @BeforeAll
    static void openDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 1500);
        } catch (Exception error) {
            assumeTrue(false, "MySQL 不可达，跳过受理事务验证");
        }
        try (var connection = DriverManager.getConnection(ROOT_URL, "root", "root"); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://localhost:3306/" + DATABASE + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        dataSource.setUsername("root");
        dataSource.setPassword("root");
        dataSource.setMaximumPoolSize(4);
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setDatabase(DATABASE);
        properties.getSession().getMysql().setMigrationEnabled(true);
        properties.getTenant().setEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
        var factory = new CustomerWorkPersistenceConfig().customerWorkSqlSessionFactory(dataSource, properties);
        // 独立测试不启动 Spring MapperScan，需要补齐没有 XML 的 BaseMapper 注册。
        for (Class<?> mapper : List.of(ChatMessageMapper.class, TicketMapper.class,
            TicketEventMapper.class, OutboxMessageMapper.class)) {
            if (!factory.getConfiguration().hasMapper(mapper)) factory.getConfiguration().addMapper(mapper);
        }
        sql = new SqlSessionTemplate(factory);
        var template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions = action -> template.execute(status -> action.get());
    }

    @AfterAll
    static void closeDatabase() throws Exception {
        if (dataSource == null) return;
        dataSource.close();
        try (var connection = DriverManager.getConnection(ROOT_URL, "root", "root"); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + DATABASE);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String table : new String[]{"cw_chat_message", "cw_ticket_event", "cw_ticket", "cw_outbox_message"}) {
                statement.executeUpdate("DELETE FROM " + table);
            }
        }
        store = spy(new MybatisChatMessageStore(sql.getMapper(ChatMessageMapper.class)));
        chatLog = spy(new ChatLogService(store));
        OutboxService outbox = new OutboxService(new MybatisOutboxStore(sql.getMapper(OutboxMessageMapper.class)),
            new OutboxProperties(), List.of());
        tickets = new TicketService(new MybatisTicketStore(sql.getMapper(TicketMapper.class), sql.getMapper(TicketEventMapper.class)),
            new OutboxTicketEventPublisher(outbox, new ObjectMapper()), transactions);
        turns = mock(ChatTurnService.class);
        when(turns.stream(anyString(), anyString(), anyString())).thenReturn(Flux.empty());
        quota = mock(SubjectQuotaGuard.class);
        when(quota.check(any(), any())).thenReturn(SubjectQuotaDecision.allow());
        registry = mock(WsSessionRegistry.class);
    }

    @Test
    void failureAfterInsert_shouldRollbackInputTicketAndOutbox_thenAllowSameId() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("simulated failure before commit");
        }).when(store).append(any());
        send(dispatch(), "tenant-a", "request-rollback").block(Duration.ofSeconds(10));
        assertEquals(0, count("cw_chat_message"));
        assertEquals(0, count("cw_ticket"));
        assertEquals(0, count("cw_ticket_event"));
        assertEquals(0, count("cw_outbox_message"));
        verifyNoInteractions(turns);
        verify(quota, never()).recordRequest(any());
        doCallRealMethod().when(store).append(any());
        send(dispatch(), "tenant-a", "request-rollback").block(Duration.ofSeconds(10));
        assertEquals(1, count("cw_chat_message"));
        assertEquals(1, count("cw_ticket"));
        assertEquals(1, count("cw_outbox_message"));
        verify(turns, times(1)).stream(anyString(), anyString(), anyString());
    }

    @Test
    void twoInstances_shouldUseUniqueKeyToCommitExactlyOneInputAndBusinessPreparation() throws Exception {
        CyclicBarrier bothReadMissing = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (reads.incrementAndGet() <= 2) bothReadMissing.await(5, TimeUnit.SECONDS);
            return result;
        }).when(chatLog).findByMessageId(anyString());
        Mono.when(send(dispatch(), "tenant-a", "request-race"), send(dispatch(), "tenant-a", "request-race"))
            .block(Duration.ofSeconds(20));
        assertEquals(1, count("cw_chat_message"));
        assertEquals(1, count("cw_ticket"));
        assertEquals(1, count("cw_ticket_event"));
        assertEquals(1, count("cw_outbox_message"));
        verify(turns, times(1)).stream(anyString(), anyString(), anyString());
        verify(quota, times(1)).recordRequest(any());
        verify(registry, times(2)).pushToUser(eq("U1"), argThat(frame -> WsFrame.TYPE_CHAT_ACCEPTED.equals(frame.type())));
    }

    @Test
    void identicalClientAndUserIdsInDifferentTenants_shouldRemainIndependent() throws Exception {
        send(dispatch(), "tenant-a", "request-tenant").block(Duration.ofSeconds(10));
        send(dispatch(), "tenant-b", "request-tenant").block(Duration.ofSeconds(10));
        assertEquals(2, count("cw_chat_message"));
        assertEquals(2, count("cw_ticket"));
        var first = TenantContext.callWith("tenant-a", () -> chatLog.historyBySession(SESSION, null, 10));
        var second = TenantContext.callWith("tenant-b", () -> chatLog.historyBySession(SESSION, null, 10));
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertNotEquals(first.get(0).messageId(), second.get(0).messageId());
        assertNull(TenantContext.get());
    }

    private ChatDispatchService dispatch() {
        return new ChatDispatchService(tickets, chatLog, turns, mock(HandoffKeywordDetector.class), registry, quota,
            ignored -> () -> { }, transactions);
    }

    private Mono<Void> send(ChatDispatchService dispatch, String tenant, String clientId) {
        return dispatch.onUserMessage(new UserPrincipal("U1", "alice", "Alice", tenant), SESSION, "查询进度", clientId);
    }

    private int count(String table) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
