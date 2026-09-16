package com.richard.fyoung.customerwork.data.chatlog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.dto.TaskPlanItem;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.data.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeDocumentReference;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 独占空库验证消息与答复信息同次落库、租户过滤和历史投影，不改变共享开发库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatAnswerEvidenceIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private final String database = "chat_answer_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper json = new ObjectMapper();
    private boolean databaseCreated;
    private PooledDataSource dataSource;
    private SqlSession session;
    private ChatLogService chatLog;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过答复信息持久化测试");
        }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            databaseCreated = true;
        }
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url(database), USER, PASSWORD);
        MybatisTestSupport.ensureSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("chat-answer-test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of()));
        config.addInterceptor(plugins);
        config.addMapper(ChatMessageMapper.class);
        String xml = "customerwork/mapper/ChatMessageMapper.xml";
        try (var input = new ClassPathResource(xml).getInputStream()) {
            new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
        }
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        chatLog = new ChatLogService(new MybatisChatMessageStore(session.getMapper(ChatMessageMapper.class)));
    }

    @AfterAll
    void cleanUp() throws Exception {
        TenantContext.clear();
        if (session != null) session.close();
        if (dataSource != null) dataSource.forceCloseAll();
        if (databaseCreated && database.startsWith("chat_answer_")) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void bodyAndEvidence_shouldRoundTripWithoutExposingInternalReferences() {
        var citation = new KnowledgeCitation("售后政策", "refund-policy", "201", 0.75);
        var reference = new KnowledgeDocumentReference(7L, 11L, 101L, 201L);
        var evidence = new ChatAnswerEvidence("MAX_ITERATIONS", List.of(citation),
            List.of(new TaskPlanItem("核对退款", "in_progress", "high")),
            List.of(new KnowledgeRetrievalSource(1, "售后政策", "refund-policy", "201",
                new BigDecimal("0.750"), reference)));
        ChatMessage saved = TenantContext.callWith("tenant-a", () -> chatLog.appendAnswer(
            "u1:session-a", "TK-a", "正在核对退款", evidence));
        TenantContext.runWith("tenant-a", () -> {
            ChatMessage replayed = chatLog.findByMessageId(saved.messageId()).orElseThrow();
            assertEquals(saved, replayed);
            assertEquals(evidence, replayed.answerEvidence());
            assertEquals(List.of(saved), chatLog.historyBySession("u1:session-a", null, 50));
            assertEquals(List.of(saved), chatLog.historyByTicket("TK-a", null, 50));
            assertTrue(chatLog.historyBySession("u1:session-b", null, 50).isEmpty());
            var publicJson = json.valueToTree(replayed);
            assertEquals("MAX_ITERATIONS", publicJson.path("finishReason").asText());
            assertEquals("refund-policy", publicJson.path("citations").path(0).path("documentId").asText());
            assertEquals("in_progress", publicJson.path("taskPlan").path(0).path("status").asText());
            for (String field : List.of("answerEvidence", "retrievalSources", "traceId", "usage", "documentReference")) {
                assertFalse(publicJson.has(field), "内部字段不能直接出站：" + field);
            }
        });
        TenantContext.runWith("tenant-b", () -> {
            assertTrue(chatLog.findByMessageId(saved.messageId()).isEmpty());
            assertTrue(chatLog.historyBySession("u1:session-a", null, 50).isEmpty());
        });
        assertEquals("tenant-a", jdbc.queryForObject(
            "SELECT tenant_id FROM cw_chat_message WHERE message_id = ?", String.class, saved.messageId()));
    }

    @Test
    void oldMessages_shouldStayUnknownAndCorruptEvidenceShouldFailVisibly() {
        TenantContext.runWith("tenant-a", () -> {
            ChatMessage old = chatLog.append("old-session", null, TicketActorType.BOT, null, "旧消息");
            ChatMessage replayed = chatLog.findByMessageId(old.messageId()).orElseThrow();
            assertNull(replayed.answerEvidence());
            var publicJson = json.valueToTree(replayed);
            assertFalse(publicJson.has("finishReason"));
            assertFalse(publicJson.has("citations"));
            assertFalse(publicJson.has("taskPlan"));
            jdbc.update("UPDATE cw_chat_message SET answer_evidence = ? WHERE message_id = ?",
                "{\"citations\":\"broken\"}", old.messageId());
            assertThrows(DataAccessException.class, () -> chatLog.findByMessageId(old.messageId()));
            assertThrows(DataAccessException.class, () -> chatLog.historyBySession("old-session", null, 50));
        });
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    }
}
