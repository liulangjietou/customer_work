package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.*;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeVersionPreviewService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatKnowledgeSourcesVO;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.ChatKnowledgeEvidenceMapper;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeDocumentReference;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalCapture;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalResult;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 独占临时库走真实 Flyway、MyBatis 租户插件、框架会话存储和文档 ACL，不写共享业务库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatKnowledgeEvidenceIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private final String database = "chat_sources_" + UUID.randomUUID().toString().replace("-", "");
    private PooledDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqlSession session;
    private MysqlAgentStateStore stateStore;
    private ChatKnowledgeSourcesService service;
    private ChatCompletionVerifier verifier;
    private boolean databaseCreated;

    @BeforeAll
    void setUpDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过聊天来源持久化测试");
        }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            databaseCreated = true;
        }
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url(database), USER, PASSWORD);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        seedKnowledge();
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("chat-evidence-test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of()));
        config.addInterceptor(plugins);
        for (Class<?> mapper : List.of(ChatKnowledgeEvidenceMapper.class, AiKnowledgeBaseMapper.class,
            AiKnowledgeBaseVersionMapper.class, AiKnowledgeBaseVersionDocumentMapper.class,
            AiKnowledgeDocumentRevisionMapper.class, AiKnowledgeDocumentMapper.class, AiKnowledgeSourceMapper.class)) {
            config.addMapper(mapper);
        }
        String xml = "mapper/ChatKnowledgeEvidenceMapper.xml";
        try (var stream = new ClassPathResource(xml).getInputStream()) {
            new XMLMapperBuilder(stream, config, xml, config.getSqlFragments()).parse();
        }
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        stateStore = new MysqlAgentStateStore(dataSource, database, "ai_chat_session_state", false);
        var preview = new KnowledgeVersionPreviewService(session.getMapper(AiKnowledgeBaseMapper.class),
            session.getMapper(AiKnowledgeBaseVersionMapper.class),
            session.getMapper(AiKnowledgeBaseVersionDocumentMapper.class),
            session.getMapper(AiKnowledgeDocumentRevisionMapper.class),
            session.getMapper(AiKnowledgeDocumentMapper.class), session.getMapper(AiKnowledgeSourceMapper.class),
            new ObjectMapper());
        service = new ChatKnowledgeSourcesService(session.getMapper(ChatKnowledgeEvidenceMapper.class),
            stateStore, preview, new ObjectMapper());
        verifier = new ChatCompletionVerifier(stateStore, service);
    }

    @BeforeEach
    void resetOwnData() {
        TenantContext.set("tenant-a");
        jdbc.update("DELETE FROM ai_chat_knowledge_evidence");
        jdbc.update("DELETE FROM ai_chat_session_state");
        jdbc.update("UPDATE ai_knowledge_document_revision SET acl_mode='PUBLIC', allowed_subject_ids='[]' WHERE id=777101");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @AfterAll
    void closeDatabase() throws Exception {
        if (session != null) session.close();
        if (dataSource != null) dataSource.forceCloseAll();
        if (databaseCreated) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void saveReadbackIsIdempotentAndIsolatesTheExactOwnerTenantAndMessage() {
        var identity = ChatKnowledgeSourcesServiceTest.identity("tenant-a", "42");
        RuntimeContext context = context(identity);
        Msg result = persistReply(context, "turn-1", "reply-1");
        capture(context, new BigDecimal("0.750"));
        // 来源写入在另一租户线程中完成，也只能落入本轮冻结的身份，结束后恢复原上下文。
        TenantContext.set("tenant-b");
        var terminal = verifier.verify(context, "turn-1", result);
        assertTrue(terminal.historySaved());
        assertTrue(terminal.knowledgeSourcesSaved(), "真实 JSON 回读后才能声明来源保存");
        assertEquals("tenant-b", TenantContext.require());
        assertTrue(service.saveConfirmed(context, "turn-1", "reply-1"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_knowledge_evidence", Integer.class));
        assertEquals("tenant-a", jdbc.queryForObject("SELECT tenant_id FROM ai_chat_knowledge_evidence", String.class));
        String saved = jdbc.queryForObject("SELECT retrievals FROM ai_chat_knowledge_evidence", String.class);
        assertFalse(saved.contains("检索瞬态正文"));
        assertFalse(saved.contains("用户提问"));

        TenantContext.set("tenant-a");
        assertEquals(ChatKnowledgeSourcesVO.Status.RECORDED, service.sources("refund", "s1", "reply-1", identity).status());
        assertEquals(ChatKnowledgeSourcesVO.Status.NOT_RECORDED, service.sources("refund", "s1", "reply-1",
            ChatKnowledgeSourcesServiceTest.identity("tenant-a", "99")).status());
        assertEquals(ChatKnowledgeSourcesVO.Status.NOT_RECORDED, service.sources("refund", "s2", "reply-1", identity).status());
        assertThrows(BizException.class, () -> service.sources("refund", "S1", "reply-1", identity));
        TenantContext.set("tenant-b");
        assertEquals(ChatKnowledgeSourcesVO.Status.NOT_RECORDED, service.sources("refund", "s1", "reply-1",
            ChatKnowledgeSourcesServiceTest.identity("tenant-b", "42")).status());
        // 同一主键不能通过重试改写本轮引用的分数、来源或轮次。
        capture(context, BigDecimal.ONE);
        assertFalse(service.saveConfirmed(context, "turn-1", "reply-1"));
        assertFalse(service.saveConfirmed(context, "other-turn", "reply-1"));
        assertEquals(saved, jdbc.queryForObject("SELECT retrievals FROM ai_chat_knowledge_evidence", String.class));
    }

    @Test
    void currentAclRevocationAndDeletedMessageImmediatelyInvalidateHistoricalSources() {
        var identity = ChatKnowledgeSourcesServiceTest.identity("tenant-a", "42");
        RuntimeContext context = context(identity);
        Msg result = persistReply(context, "turn-1", "reply-1");
        capture(context, BigDecimal.ONE);
        assertTrue(verifier.verify(context, "turn-1", result).knowledgeSourcesSaved());
        assertEquals("历史正文", service.preview("refund", "s1", "reply-1", 0, identity).content());
        jdbc.update("UPDATE ai_knowledge_document_revision SET acl_mode='RESTRICTED', "
            + "allowed_subject_types='ADMIN_USER', allowed_subject_ids='[\"99\"]' WHERE id=777101");
        assertEquals(ResultCode.FORBIDDEN, assertThrows(BizException.class,
            () -> service.preview("refund", "s1", "reply-1", 0, identity)).getResultCode());
        var source = service.sources("refund", "s1", "reply-1", identity).retrievals().get(0).sources().get(0);
        assertEquals(ChatKnowledgeSourcesVO.SourceStatus.FORBIDDEN, source.status());
        assertNull(source.document());
        assertNull(source.knowledgeBaseName());
        jdbc.update("DELETE FROM ai_chat_session_state");
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, assertThrows(BizException.class,
            () -> service.sources("refund", "s1", "reply-1", identity)).getResultCode());
    }

    private RuntimeContext context(AgentInvocationIdentity identity) {
        String stateUserId = AgentInvocationIdentityContext.callWith(identity,
            () -> AgentMemoryScope.current("refund").stateUserId());
        RuntimeContext context = RuntimeContext.builder().userId(stateUserId).sessionId("s1").build();
        context.put(AgentInvocationIdentity.class, identity);
        return context;
    }

    private Msg persistReply(RuntimeContext context, String turnId, String messageId) {
        Msg result = Msg.builder().id(messageId).role(MsgRole.ASSISTANT).textContent("用户答复")
            .generateReason(GenerateReason.MODEL_STOP).build();
        var state = AgentState.builder().userId(context.getUserId()).sessionId(context.getSessionId())
            .context(List.of(Msg.builder().id(turnId).role(MsgRole.USER).textContent("用户提问").build(), result)).build();
        stateStore.save(context.getUserId(), context.getSessionId(), "agent_state", state);
        return result;
    }

    private void capture(RuntimeContext context, BigDecimal score) {
        var capture = new KnowledgeRetrievalCapture();
        capture.record("refund", KnowledgeRetrievalResult.completed("检索瞬态正文", List.of(
            new KnowledgeRetrievalSource(1, "售后知识", "refund", "chunk-1", score,
                new KnowledgeDocumentReference(777007L, 777070L, 777100L, 777200L)))));
        KnowledgeRetrievalCapture.bind(context, capture);
    }

    private void seedKnowledge() {
        jdbc.update("INSERT INTO ai_knowledge_base(id,tenant_id,kb_name,base_url,app_id,api_key) "
            + "VALUES(777007,'tenant-a','Preview corpus','https://example.invalid','test','test')");
        jdbc.update("INSERT INTO ai_knowledge_base_version(id,tenant_id,knowledge_base_id,version_no,"
            + "base_url,app_id,api_key,snapshot_hash,document_count) "
            + "VALUES(777070,'tenant-a',777007,3,'https://example.invalid','test','test','snapshot',1)");
        jdbc.update("INSERT INTO ai_knowledge_source(id,tenant_id,knowledge_base_id,source_code,"
            + "source_name,default_acl_json) VALUES(777010,'tenant-a',777007,'policy','Policy source','{}')");
        jdbc.update("INSERT INTO ai_knowledge_document(id,tenant_id,knowledge_base_id,source_id,external_id,"
            + "current_revision_id) VALUES(777020,'tenant-a',777007,777010,'refund',777101)");
        jdbc.update("INSERT INTO ai_knowledge_document_revision(id,tenant_id,document_id,source_id,"
            + "operation,title,content,allowed_subject_ids,allowed_channels) VALUES"
            + "(777100,'tenant-a',777020,777010,'UPSERT','Historical policy','历史正文','[]','[]'),"
            + "(777101,'tenant-a',777020,777010,'UPSERT','Current policy','当前正文','[]','[]')");
        jdbc.update("INSERT INTO ai_knowledge_base_version_document(tenant_id,knowledge_base_version_id,"
            + "document_revision_id,source_id,external_id) VALUES('tenant-a',777070,777100,777010,'refund')");
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
