package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentChunk;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 既有 mock 只验证先删后写，没有验证历史引用的标识稳定性与跨版本唯一约束。 */
class KnowledgeProjectionIdentityIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private String database;
    private DriverManagerDataSource dataSource;
    private SqlSession session;
    private KnowledgeChunkMapper cwChunks;
    private KnowledgeProjectionService service;

    @BeforeEach
    void setUp() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过知识投影标识测试");
        }
        String candidate = "kb_projection_identity_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate);
            database = candidate;
        }
        dataSource = new DriverManagerDataSource(url(database), USER, PASSWORD);
        var properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("projection-identity", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of()));
        configuration.addInterceptor(plugins);
        for (String location : List.of("customerwork/mapper/KnowledgeChunkMapper.xml",
            "customerwork/mapper/KnowledgeVersionMapper.xml")) {
            try (var input = new ClassPathResource(location).getInputStream()) {
                new XMLMapperBuilder(input, configuration, location, configuration.getSqlFragments()).parse();
            }
        }
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        cwChunks = session.getMapper(KnowledgeChunkMapper.class);
        var provider = mock(KnowledgeProjectionGatewayProvider.class);
        when(provider.get()).thenReturn(new KnowledgeProjectionGateway(cwChunks,
            session.getMapper(KnowledgeVersionMapper.class)));
        TenantContext.set("tenant-a");
        service = fixture(provider);
    }

    @AfterEach
    void cleanUp() throws Exception {
        TenantContext.clear();
        if (session != null) session.close();
        if (database != null) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void reprojectingImmutableVersionPreservesHistoricalChunkReference() {
        service.project(7L, 70L);
        KnowledgeChunkDO original = chunk(70L);
        service.project(7L, 70L);
        KnowledgeChunkDO current = chunk(70L);
        assertEquals(original.getId(), current.getId(), "重复投影同一不可变版本不能使已保存的消息引用失效");
        assertEquals(original.getContent(), current.getContent());
    }

    @Test
    void twoVersionsCanProjectTheSameImmutableRevisionIndependently() {
        service.project(7L, 70L);
        KnowledgeChunkDO first = chunk(70L);
        assertDoesNotThrow(() -> service.project(7L, 71L), "新配置版本复用旧修订是有效业务，不能撞分片唯一键");
        KnowledgeChunkDO second = chunk(71L);
        assertNotEquals(first.getId(), second.getId());
        assertEquals(first.getDocRevisionId(), second.getDocRevisionId());
        assertEquals(first.getContent(), second.getContent());
        assertEquals(first.getId(), chunk(70L).getId(), "新版本投影不能替换旧版本分片");
    }

    private KnowledgeChunkDO chunk(long versionId) {
        return cwChunks.selectOne(new QueryWrapper<KnowledgeChunkDO>().eq("kb_version_id", versionId));
    }

    private KnowledgeProjectionService fixture(KnowledgeProjectionGatewayProvider provider) {
        var bases = mock(AiKnowledgeBaseMapper.class);
        var versions = mock(AiKnowledgeBaseVersionMapper.class);
        var members = mock(AiKnowledgeBaseVersionDocumentMapper.class);
        var revisions = mock(AiKnowledgeDocumentRevisionMapper.class);
        var chunks = mock(AiKnowledgeDocumentChunkMapper.class);
        var base = new AiKnowledgeBase();
        base.setId(7L);
        base.setDeleted(0);
        base.setStatus(1);
        base.setKbName("售后政策");
        when(bases.selectByIdForUpdate(7L)).thenReturn(base);
        for (long id : List.of(70L, 71L)) {
            var version = new AiKnowledgeBaseVersion();
            version.setId(id);
            version.setTenantId("tenant-a");
            version.setVersionNo((int) id - 69);
            version.setKnowledgeBaseId(7L);
            when(versions.selectById(id)).thenReturn(version);
        }
        var member = new AiKnowledgeBaseVersionDocument();
        member.setDocumentRevisionId(700L);
        member.setKnowledgeBaseVersionId(70L);
        member.setTenantId("tenant-a");
        member.setSourceId(8L);
        member.setExternalId("refund-policy");
        when(members.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            query.getSqlSegment();
            member.setKnowledgeBaseVersionId((Long) query.getParamNameValuePairs().values().iterator().next());
            return List.of(member);
        });
        var revision = new AiKnowledgeDocumentRevision();
        revision.setId(700L);
        revision.setTenantId("tenant-a");
        revision.setSourceId(8L);
        revision.setDocumentId(20L);
        revision.setContent("七天无理由退款说明");
        revision.setOperation("UPSERT");
        revision.setAclMode("PUBLIC");
        when(revisions.selectBatchIds(any())).thenReturn(List.of(revision));
        var chunk = new AiKnowledgeDocumentChunk();
        chunk.setId(7000L);
        chunk.setTenantId("tenant-a");
        chunk.setDocumentRevisionId(700L);
        chunk.setChunkIndex(0);
        chunk.setContent("七天无理由退款说明");
        chunk.setEmbedding("[1.0,0.0]");
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
        var documents = mock(AiKnowledgeDocumentMapper.class);
        var document = new AiKnowledgeDocument();
        document.setId(20L);
        document.setTenantId("tenant-a");
        document.setKnowledgeBaseId(7L);
        document.setSourceId(8L);
        document.setExternalId("refund-policy");
        document.setCurrentRevisionId(700L);
        document.setDeleted(0);
        when(documents.selectBatchIds(any())).thenReturn(List.of(document));
        var sources = mock(AiKnowledgeSourceMapper.class);
        var source = new AiKnowledgeSource();
        source.setId(8L);
        source.setTenantId("tenant-a");
        source.setKnowledgeBaseId(7L);
        source.setStatus(1);
        source.setDeleted(0);
        when(sources.selectBatchIds(any())).thenReturn(List.of(source));
        return new KnowledgeProjectionService(provider, new KnowledgeProjectionAccessGuard(bases, provider),
            versions, members, revisions, chunks, documents, sources, new ObjectMapper());
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
