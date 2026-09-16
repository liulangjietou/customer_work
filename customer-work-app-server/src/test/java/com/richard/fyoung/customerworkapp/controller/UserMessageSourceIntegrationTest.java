package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.data.chatlog.ChatAnswerEvidence;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.MybatisChatMessageStore;
import com.richard.fyoung.customerwork.data.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeDocumentReference;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.data.ticket.MybatisTicketStore;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.dao.UserMessageSourceDao;
import com.richard.fyoung.customerworkapp.service.UserMessageSourceService;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 JWT → HTTP → 会话/消息存储 → 授权 SQL，隔离数据库验证持久引用不能跨用户或租户读取。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserMessageSourceIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private static final String SESSION = "uU1:source-it";
    private static final String MESSAGE = "MSG-source-it";
    private static final String BASE = "/api/customer/user/sessions/" + SESSION + "/messages/" + MESSAGE + "/sources";
    private final String database = "cw_source_http_" + UUID.randomUUID().toString().replace("-", "");
    private boolean databaseCreated;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private MybatisTicketStore ticketStore;
    private MybatisChatMessageStore messages;
    private UserJwtService jwt;
    private WebTestClient client;
    private WebTestClient withoutTenantPlugin;

    @BeforeAll
    void openDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过客户原文真实读取链路");
        }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            databaseCreated = true;
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url(database));
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(3);
        var properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setDatabase(database);
        properties.getSession().getMysql().setMigrationEnabled(true);
        properties.getTenant().setEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
        var factory = new CustomerWorkPersistenceConfig().customerWorkSqlSessionFactory(dataSource, properties);
        for (Class<?> mapper : List.of(ChatMessageMapper.class, TicketMapper.class, TicketEventMapper.class)) {
            if (!factory.getConfiguration().hasMapper(mapper)) factory.getConfiguration().addMapper(mapper);
        }
        sql = new SqlSessionTemplate(factory);
        jdbc = new JdbcTemplate(dataSource);
        ticketStore = new MybatisTicketStore(sql.getMapper(TicketMapper.class), sql.getMapper(TicketEventMapper.class));
        messages = new MybatisChatMessageStore(sql.getMapper(ChatMessageMapper.class));
        jwt = new UserJwtService(properties);
        client = client(sql);
        // 默认配置不安装租户 SQL 插件；不能用启用插件的测试替代应用的真实默认行为。
        var defaultProperties = new CustomerWorkProperties();
        var defaultFactory = new CustomerWorkPersistenceConfig().customerWorkSqlSessionFactory(dataSource, defaultProperties);
        for (Class<?> mapper : List.of(ChatMessageMapper.class, TicketMapper.class, TicketEventMapper.class)) {
            if (!defaultFactory.getConfiguration().hasMapper(mapper)) defaultFactory.getConfiguration().addMapper(mapper);
        }
        withoutTenantPlugin = client(new SqlSessionTemplate(defaultFactory));
    }

    private WebTestClient client(SqlSessionTemplate session) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("chunks", session.getMapper(KnowledgeChunkMapper.class));
        beans.registerSingleton("customerWorkDataSource", dataSource);
        var service = new UserMessageSourceService(new UserMessageSourceDao(beans.getBeanProvider(DataSource.class)),
            beans.getBeanProvider(KnowledgeChunkMapper.class));
        return WebTestClient.bindToController(new UserMessageSourceController(service))
            .webFilter(new UserAuthWebFilter(jwt)).build();
    }

    @BeforeEach
    void prepareSavedAnswer() {
        for (String table : List.of("cw_chat_message", "cw_ticket", "cw_knowledge_chunk", "cw_knowledge_version")) {
            jdbc.update("DELETE FROM " + table);
        }
        saveMessage("tenant-a", true);
        jdbc.update("INSERT INTO cw_knowledge_version(tenant_id,kb_version_id,kb_code,kb_name,version_no,"
            + "dimensions,synced_at_ms,created_at_ms,updated_at_ms,access_status) "
            + "VALUES('tenant-a',70,'7','当前授权知识库',3,1,1,1,1,'READY')");
        jdbc.update("INSERT INTO cw_knowledge_chunk(id,tenant_id,kb_version_id,doc_revision_id,chunk_index,"
            + "content,embedding,dimensions,acl_mode,document_title,source_version,created_at_ms,updated_at_ms) "
            + "VALUES(901,'tenant-a',70,700,0,'历史段落 <script>plain</script>',X'00000000',1,'PUBLIC',"
            + "'实际历史标题','historical-v1',1,1)");
    }

    @AfterAll
    void closeDatabase() throws Exception {
        TenantContext.clear();
        if (dataSource != null) dataSource.close();
        if (databaseCreated) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void readsSavedHistoricalSourceAndRechecksCommittedRevocationOnEveryRequest() {
        read("tenant-a", "U1", "").expectStatus().isOk().expectBody()
            .jsonPath("$[0].title").isEqualTo("实际历史标题").jsonPath("$[0].content").doesNotExist();
        read("tenant-a", "U1", "/0").expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody()
            .jsonPath("$.content").isEqualTo("历史段落 <script>plain</script>")
            .jsonPath("$.versionNo").isEqualTo(3).jsonPath("$.sourceVersion").isEqualTo("historical-v1");
        TenantContext.runWith("tenant-a", () -> sql.getMapper(KnowledgeVersionMapper.class).blockByKnowledgeBase("7", 2L));
        read("tenant-a", "U1", "/0").expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("UNAVAILABLE").jsonPath("$.title").isEmpty()
            .jsonPath("$.knowledgeBase").isEmpty().jsonPath("$.content").isEmpty();
    }

    @Test
    void identicalUserAndSessionNamesCannotResolveAnotherTenantsMessageOrReference() {
        read("tenant-a", "U2", "/0").expectStatus().isNotFound();
        read("tenant-b", "U1", "/0").expectStatus().isNotFound();
        saveMessage("tenant-b", true);
        readMessage("tenant-b", "U1", MESSAGE, "/0").expectStatus().isNotFound();
        read("tenant-b", "U1", "/0").expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("UNAVAILABLE").jsonPath("$.content").isEmpty();
        read("tenant-a", "U1", "/0").expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("AVAILABLE");
    }

    @Test
    void oldMessageAndPurgedHistoricalChunkHaveDifferentTruthfulStates() {
        saveMessage("tenant-b", false);
        read("tenant-b", "U1", "").expectStatus().isOk().expectBody().json("[]");
        read("tenant-b", "U1", "/0").expectStatus().isNotFound();
        jdbc.update("DELETE FROM cw_knowledge_chunk WHERE id=901");
        read("tenant-a", "U1", "/0").expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("UNAVAILABLE").jsonPath("$.title").isEmpty()
            .jsonPath("$.content").isEmpty();
    }

    @Test
    void disabledTenantPluginStillReadsTheLegitimateOwnersSavedSource() {
        prepareDefaultTenantAnswer();
        readWithoutTenantPlugin("").expectStatus().isOk().expectBody()
            .jsonPath("$[0].status").isEqualTo("AVAILABLE")
            .jsonPath("$[0].content").doesNotExist();
        readWithoutTenantPlugin("/0").expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody()
            .jsonPath("$.content").isEqualTo("历史段落 <script>plain</script>");
    }

    @Test
    void disabledTenantPluginCannotExposeAnotherTenantsRealSavedReference() {
        prepareDefaultTenantAnswer();
        jdbc.update("UPDATE cw_knowledge_version SET tenant_id='tenant-b'");
        jdbc.update("UPDATE cw_knowledge_chunk SET tenant_id='tenant-b'");
        readWithoutTenantPlugin("").expectStatus().isOk().expectBody()
            .json("[{\"sourceIndex\":0,\"status\":\"UNAVAILABLE\",\"title\":null,"
                + "\"knowledgeBase\":null,\"versionNo\":null,\"sourceVersion\":null}]", true);
        readWithoutTenantPlugin("/0").expectStatus().isOk().expectBody()
            .json("{\"sourceIndex\":0,\"status\":\"UNAVAILABLE\",\"title\":null,"
                + "\"knowledgeBase\":null,\"versionNo\":null,\"sourceVersion\":null,\"content\":null}", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"message", "ticket", "both"})
    void disabledTenantPluginRejectsMessageOrTicketOutsideTheJwtTenant(String mismatch) {
        prepareDefaultTenantAnswer();
        if (mismatch.equals("message") || mismatch.equals("both")) {
            jdbc.update("UPDATE cw_chat_message SET tenant_id='tenant-b'");
        }
        if (mismatch.equals("ticket") || mismatch.equals("both")) {
            jdbc.update("UPDATE cw_ticket SET tenant_id='tenant-b'");
        }
        for (String suffix : List.of("", "/0")) {
            readWithoutTenantPlugin(suffix).expectStatus().isNotFound()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        }
    }

    @Test
    void tenantCaseVariantsStillReferToTheSameDatabaseTenant() {
        prepareDefaultTenantAnswer();
        jdbc.update("UPDATE cw_chat_message SET tenant_id='DEFAULT'");
        jdbc.update("UPDATE cw_ticket SET tenant_id='Default'");
        readWithoutTenantPlugin("/0").expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("AVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-session", "other-user", "user-case", "ticket-session", "ticket-session-case",
        "missing-message", "message-case", "message-session", "message-session-case", "message-ticket", "ticket-case", "user-message"})
    void rejectsForeignResourcesAndCaseInsensitiveIdMatchesWithoutTheTenantPlugin(String mismatch) {
        prepareDefaultTenantAnswer();
        switch (mismatch) {
            case "missing-session" -> jdbc.update("DELETE FROM cw_ticket");
            case "other-user" -> jdbc.update("UPDATE cw_ticket SET user_id='U2'");
            case "user-case" -> jdbc.update("UPDATE cw_ticket SET user_id='u1'");
            case "ticket-session" -> jdbc.update("UPDATE cw_ticket SET session_id='uU1:other-session'");
            case "ticket-session-case" -> jdbc.update("UPDATE cw_ticket SET session_id=?", SESSION.toUpperCase());
            case "missing-message" -> jdbc.update("DELETE FROM cw_chat_message");
            case "message-case" -> jdbc.update("UPDATE cw_chat_message SET message_id=?", messageId("default").toUpperCase());
            case "message-session" -> jdbc.update("UPDATE cw_chat_message SET session_id='uU1:other-session'");
            case "message-session-case" -> jdbc.update("UPDATE cw_chat_message SET session_id=?", SESSION.toUpperCase());
            case "message-ticket" -> jdbc.update("UPDATE cw_chat_message SET ticket_id='TK-other'");
            case "ticket-case" -> jdbc.update("UPDATE cw_chat_message SET ticket_id='TK-DEFAULT'");
            case "user-message" -> jdbc.update("UPDATE cw_chat_message SET sender_type='USER'");
            default -> throw new AssertionError("unknown mismatch");
        }
        for (String suffix : List.of("", "/0")) {
            readWithoutTenantPlugin(suffix).expectStatus().isNotFound()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        }
    }

    @Test
    void legacyMessageWithoutTicketIdStillUsesTheOwnedSession() {
        prepareDefaultTenantAnswer();
        jdbc.update("UPDATE cw_chat_message SET ticket_id=NULL");
        readWithoutTenantPlugin("/0").expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("AVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sql-null", "json-null"})
    void oldSavedMessagesWithoutEvidenceDoNotReconstructReferencesFromText(String noEvidence) {
        prepareDefaultTenantAnswer();
        jdbc.update("UPDATE cw_chat_message SET answer_evidence=?", noEvidence.equals("sql-null") ? null : "null");
        readWithoutTenantPlugin("").expectStatus().isOk().expectBody().json("[]");
        readWithoutTenantPlugin("/0").expectStatus().isNotFound();
    }

    @Test
    void incompatibleSavedEvidenceIsAStorageFailureInsteadOfAnEmptyAnswer() {
        prepareDefaultTenantAnswer();
        jdbc.update("UPDATE cw_chat_message SET answer_evidence='[1]'");
        readWithoutTenantPlugin("").expectStatus().isEqualTo(503)
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private void prepareDefaultTenantAnswer() {
        jdbc.update("DELETE FROM cw_chat_message");
        jdbc.update("DELETE FROM cw_ticket");
        saveMessage("default", true);
        jdbc.update("UPDATE cw_knowledge_version SET tenant_id='default'");
        jdbc.update("UPDATE cw_knowledge_chunk SET tenant_id='default'");
    }

    private WebTestClient.ResponseSpec readWithoutTenantPlugin(String suffix) {
        return withoutTenantPlugin.get().uri(BASE.replace(MESSAGE, messageId("default")) + suffix)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue("U1", "alice", "Alice", "default")).exchange();
    }

    private void saveMessage(String tenant, boolean withReference) {
        TenantContext.runWith(tenant, () -> {
            String ticketId = "TK-" + tenant;
            ticketStore.save(Ticket.create(ticketId, SESSION, "U1", "原文核对", TicketCategory.CONSULT));
            ChatMessage message = ChatMessage.of(messageId(tenant), SESSION, ticketId, TicketActorType.BOT, null, "答复与参考线索");
            if (withReference) {
                var source = new KnowledgeRetrievalSource(1, "不可信的旧库名", "700", "901", null,
                    new KnowledgeDocumentReference(7L, 70L, 700L, 901L));
                message = message.withAnswerEvidence(new ChatAnswerEvidence("MODEL_STOP", List.of(), List.of(), List.of(source)));
            }
            messages.append(message);
        });
    }

    private WebTestClient.ResponseSpec read(String tenant, String user, String suffix) {
        return readMessage(tenant, user, messageId(tenant), suffix);
    }

    private WebTestClient.ResponseSpec readMessage(String tenant, String user, String messageId, String suffix) {
        String path = BASE.replace(MESSAGE, messageId);
        return client.get().uri(path + suffix).header(HttpHeaders.AUTHORIZATION,
            "Bearer " + jwt.issue(user, "alice", "Alice", tenant)).exchange();
    }

    private String messageId(String tenant) {
        return "tenant-a".equals(tenant) ? MESSAGE : MESSAGE + "-" + tenant;
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
