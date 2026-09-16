package com.richard.fyoung.customerwork.data.knowledge;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.data.knowledge.vector.MybatisVectorStore;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorCodec;
import com.richard.fyoung.customerwork.data.rag.ManagedKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 SQL 验证正文读取依赖完整的可读版本，不能只相信分片上的历史 PUBLIC。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgePublicProjectionIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private final String database = "cw_public_projection_" + UUID.randomUUID().toString().replace("-", "");
    private boolean databaseCreated;
    private PooledDataSource dataSource;
    private SqlSession session;
    private KnowledgeChunkMapper chunks;
    private KnowledgeVersionMapper versions;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过公开知识投影测试");
        }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            databaseCreated = true;
        }
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url(database), USER, PASSWORD);
        MybatisTestSupport.ensureSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        session = openSession(true);
        chunks = session.getMapper(KnowledgeChunkMapper.class);
        versions = session.getMapper(KnowledgeVersionMapper.class);
    }

    private SqlSession openSession(boolean tenantPlugin) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("public-projection", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        if (tenantPlugin) {
            var plugins = new MybatisPlusInterceptor();
            plugins.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of()));
            configuration.addInterceptor(plugins);
        }
        for (String xml : List.of("customerwork/mapper/KnowledgeChunkMapper.xml",
                                 "customerwork/mapper/KnowledgeVersionMapper.xml")) {
            try (var input = new ClassPathResource(xml).getInputStream()) {
                new XMLMapperBuilder(input, configuration, xml, configuration.getSqlFragments()).parse();
            }
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
    }

    @AfterAll
    void cleanUp() throws Exception {
        TenantContext.clear();
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
    void orphanPublicChunkCannotEnterSearchOrMaterialize() {
        jdbc.update("INSERT INTO cw_knowledge_chunk(id,tenant_id,kb_version_id,doc_revision_id,chunk_index,"
            + "content,embedding,dimensions,acl_mode,created_at_ms,updated_at_ms) "
            + "VALUES(910,'tenant-a',91,9100,0,'孤立旧公开正文',X'00000000',1,'PUBLIC',1,1)");
        TenantContext.runWith("tenant-a", () -> assertAll(
            () -> assertTrue(chunks.selectPublicPartitions(91L).isEmpty(), "没有可读版本不能成为检索分区"),
            () -> assertTrue(chunks.selectVectorsByPartitions(91L, List.of(9100L), 0L, 10).isEmpty(),
                "撤权后的旧分区不能继续参与打分"),
            () -> assertTrue(chunks.selectByIds(List.of(910L)).isEmpty(), "迟到的向量命中不能绕过版本门禁读取正文")
        ));
    }

    @Test
    void blockedVersionStopsEveryReadAndReadyPreviewChecksExactReference() {
        seedVersion(92L, "92");
        jdbc.update("INSERT INTO cw_knowledge_chunk(id,tenant_id,kb_version_id,doc_revision_id,chunk_index,"
            + "content,embedding,dimensions,acl_mode,document_title,source_version,created_at_ms,updated_at_ms) "
            + "VALUES(920,'tenant-a',92,9200,0,'历史原文 <script>plain</script>',X'00000000',1,'PUBLIC',"
            + "'历史退款政策','source-v1',1,1)");
        TenantContext.runWith("tenant-a", () -> {
            assertUnavailable(92L, 9200L, 920L);
            jdbc.update("UPDATE cw_knowledge_version SET access_status='READY' WHERE kb_version_id=92");
            assertEquals(List.of(9200L), chunks.selectPublicPartitions(92L));
            assertEquals(1, chunks.selectVectorsByPartitions(92L, List.of(9200L), 0L, 10).size());
            assertEquals(1, chunks.selectByIds(List.of(920L)).size());
            KnowledgePublicSource summary = chunks.findPublicSource(TenantContext.require(), "92", 92L, 9200L, 920L, false);
            assertNotNull(summary);
            assertEquals("历史退款政策", summary.title());
            assertEquals(3, summary.versionNo());
            assertEquals("source-v1", summary.sourceVersion());
            assertNull(summary.content(), "来源列表不取正文");
            assertEquals("历史原文 <script>plain</script>",
                chunks.findPublicSource(TenantContext.require(), "92", 92L, 9200L, 920L, true).content());
            assertNull(chunks.findPublicSource(TenantContext.require(), "7", 92L, 9200L, 920L, true));
            assertNull(chunks.findPublicSource(TenantContext.require(), "92", 93L, 9200L, 920L, true));
            assertNull(chunks.findPublicSource(TenantContext.require(), "92", 92L, 9201L, 920L, true));
            assertNull(chunks.findPublicSource(TenantContext.require(), "92", 92L, 9200L, 921L, true));
            jdbc.update("UPDATE cw_knowledge_chunk SET acl_mode='PRIVATE' WHERE id=920");
            assertUnavailable(92L, 9200L, 920L);
            jdbc.update("UPDATE cw_knowledge_chunk SET acl_mode='PUBLIC' WHERE id=920");
            versions.blockByKnowledgeBase("92", 2L);
            assertUnavailable(92L, 9200L, 920L);
        });
        jdbc.update("UPDATE cw_knowledge_version SET access_status='READY' WHERE kb_version_id=92");
        TenantContext.runWith("tenant-b", () -> assertUnavailable(92L, 9200L, 920L));
    }

    @Test
    void projectionUpsertPreservesIdsAndDeletesOnlyUnretainedTargetVersionRows() {
        seedVersion(93L, "93");
        seedVersion(94L, "93");
        TenantContext.runWith("tenant-a", () -> {
            KnowledgeChunkDO original = projectionChunk(93L, 9300L, "历史正文");
            chunks.upsertProjection(original);
            assertNotNull(original.getId());
            KnowledgeChunkDO repeated = projectionChunk(93L, 9300L, "历史正文");
            chunks.upsertProjection(repeated);
            assertEquals(original.getId(), repeated.getId(), "重复投影必须回填原有标识");
            KnowledgeChunkDO secondVersion = projectionChunk(94L, 9300L, "历史正文");
            chunks.upsertProjection(secondVersion);
            assertNotEquals(original.getId(), secondVersion.getId());
            KnowledgeChunkDO obsolete = projectionChunk(93L, 9310L, "已移除段落");
            chunks.upsertProjection(obsolete);
            chunks.deleteVersionChunksExcept(93L, List.of(original.getId()));
            assertNotNull(chunks.selectById(original.getId()));
            assertNull(chunks.selectById(obsolete.getId()));
            assertNotNull(chunks.selectById(secondVersion.getId()));
            chunks.deleteVersionChunksExcept(94L, List.of());
            assertNull(chunks.selectById(secondVersion.getId()));
            assertNotNull(chunks.selectById(original.getId()));
        });
    }

    @Test
    void pluginDisabledStillRestrictsEveryReadToAuthenticatedTenant() throws Exception {
        seedReadyProjection("tenant-b", 96L, 960L, "其他租户公开正文");
        try (var unfilteredSession = openSession(false)) {
            var mapper = unfilteredSession.getMapper(KnowledgeChunkMapper.class);
            TenantContext.runWith("tenant-a", () -> assertAll(
                () -> assertTrue(mapper.selectPublicPartitions(96L).isEmpty(), "PUBLIC 不代表跨租户公开"),
                () -> assertTrue(mapper.selectVectorsByPartitions(96L, List.of(9600L), 0L, 10).isEmpty()),
                () -> assertTrue(mapper.selectByIds(List.of(960L)).isEmpty()),
                () -> assertNull(mapper.findPublicSource(TenantContext.require(), "96", 96L, 9600L, 960L, true))
            ));
            TenantContext.runWith("tenant-b", () -> assertEquals("其他租户公开正文",
                mapper.findPublicSource(TenantContext.require(), "96", 96L, 9600L, 960L, true).content()));
        }
    }

    @Test
    void managedRetrievalWithoutPluginCapturesTenantBeforeAsyncSubscription() throws Exception {
        seedReadyProjection("retrieval-a", 97L, 970L, "本人租户答复依据");
        seedReadyProjection("retrieval-b", 98L, 980L, "其他租户答复依据");
        try (var unfilteredSession = openSession(false)) {
            var mapper = unfilteredSession.getMapper(KnowledgeChunkMapper.class);
            var embedding = mock(EmbeddingClient.class);
            when(embedding.embedQuery(anyString())).thenReturn(new float[] {1f});
            var knowledge = new ManagedKnowledge(new MybatisVectorStore(mapper), mapper,
                unfilteredSession.getMapper(KnowledgeVersionMapper.class), embedding, List.of());
            var pending = TenantContext.callWith("retrieval-a",
                () -> knowledge.retrieve("退款", RetrieveConfig.builder().limit(10).build()));
            TenantContext.runWith("retrieval-b", () -> {
                var documents = pending.block();
                assertNotNull(documents);
                assertEquals(1, documents.size(), "默认未安装租户插件也只能召回发起租户的资料");
                assertTrue(documents.get(0).getMetadata().getContentText().endsWith("本人租户答复依据"));
                assertEquals("retrieval-b", TenantContext.require(), "异步查询不能污染订阅调用方");
            });
        }
    }

    private void seedReadyProjection(String tenant, long version, long chunk, String content) {
        jdbc.update("INSERT INTO cw_knowledge_version(tenant_id,kb_version_id,kb_code,kb_name,version_no,"
            + "access_status,dimensions,synced_at_ms,created_at_ms,updated_at_ms) "
            + "VALUES(?,?,?,'租户知识库',1,'READY',1,1,1,1)", tenant, version, String.valueOf(version));
        jdbc.update("INSERT INTO cw_knowledge_chunk(id,tenant_id,kb_version_id,doc_revision_id,chunk_index,"
            + "content,embedding,dimensions,acl_mode,external_id,created_at_ms,updated_at_ms) "
            + "VALUES(?,?,?,?,0,?,?,1,'PUBLIC','refund-policy',1,1)",
            chunk, tenant, version, chunk * 10, content, VectorCodec.encode(new float[] {1f}));
    }

    private void assertUnavailable(long versionId, long revisionId, long chunkId) {
        assertAll(
            () -> assertTrue(chunks.selectPublicPartitions(versionId).isEmpty()),
            () -> assertTrue(chunks.selectVectorsByPartitions(versionId, List.of(revisionId), 0L, 10).isEmpty()),
            () -> assertTrue(chunks.selectByIds(List.of(chunkId)).isEmpty()),
            () -> assertNull(chunks.findPublicSource(TenantContext.require(), String.valueOf(versionId), versionId, revisionId, chunkId, true))
        );
    }

    private void seedVersion(long versionId, String knowledgeBaseId) {
        jdbc.update("INSERT INTO cw_knowledge_version(tenant_id,kb_version_id,kb_code,kb_name,version_no,"
            + "dimensions,synced_at_ms,created_at_ms,updated_at_ms) VALUES('tenant-a',?,?,'售后知识库',3,1,1,1,1)",
            versionId, knowledgeBaseId);
    }

    private KnowledgeChunkDO projectionChunk(long versionId, long revisionId, String body) {
        var chunk = new KnowledgeChunkDO();
        chunk.setKbVersionId(versionId);
        chunk.setDocRevisionId(revisionId);
        chunk.setChunkIndex(0);
        chunk.setContent(body);
        chunk.setEmbedding(new byte[4]);
        chunk.setDimensions(1);
        chunk.setAclMode("PUBLIC");
        chunk.setCreatedAtMs(1L);
        chunk.setUpdatedAtMs(1L);
        return chunk;
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
