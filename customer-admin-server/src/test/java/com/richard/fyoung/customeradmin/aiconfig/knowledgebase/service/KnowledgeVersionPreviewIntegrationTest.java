package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgePreviewStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 独占随机数据库：真实迁移、租户 SQL、版本成员和当前 ACL 撤回，不写共享知识库。 */
class KnowledgeVersionPreviewIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");

    @Test
    void exactVersionPreviewAndCurrentAccessRevocationUseRealTenantScopedSql() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过版本预览持久化测试");
        }
        String database = "kb_preview_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        var dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url(database), USER, PASSWORD);
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .placeholderReplacement(false).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            // 再运行镜像内容仍只有一个权限；不根据已有查看/编辑权限隐式授权。
            new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V105__knowledge_source_preview_permission.sql")).execute(dataSource);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_permission "
                + "WHERE perm_code='knowledge-base:source-preview'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission rp "
                + "JOIN sys_permission p ON p.id=rp.permission_id "
                + "WHERE p.perm_code='knowledge-base:source-preview'", Integer.class));
            seed(jdbc);
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("knowledge-preview-test", new JdbcTransactionFactory(), dataSource));
            config.setMapUnderscoreToCamelCase(true);
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            var plugins = new MybatisPlusInterceptor();
            plugins.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of()));
            plugins.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            config.addInterceptor(plugins);
            for (Class<?> mapper : List.of(AiKnowledgeBaseMapper.class, AiKnowledgeBaseVersionMapper.class,
                AiKnowledgeBaseVersionDocumentMapper.class, AiKnowledgeDocumentRevisionMapper.class,
                AiKnowledgeDocumentMapper.class, AiKnowledgeSourceMapper.class)) {
                config.addMapper(mapper);
            }
            var factory = new MybatisSqlSessionFactoryBuilder().build(config);
            try (var session = factory.openSession(true)) {
                var service = new KnowledgeVersionPreviewService(session.getMapper(AiKnowledgeBaseMapper.class),
                    session.getMapper(AiKnowledgeBaseVersionMapper.class),
                    session.getMapper(AiKnowledgeBaseVersionDocumentMapper.class),
                    session.getMapper(AiKnowledgeDocumentRevisionMapper.class),
                    session.getMapper(AiKnowledgeDocumentMapper.class),
                    session.getMapper(AiKnowledgeSourceMapper.class), new ObjectMapper());
                TenantContext.set("tenant-a");
                var identity = identity("tenant-a");
                assertEquals("历史正文", service.preview(777007L, 777070L, 777100L, identity).content());
                assertEquals(1, service.documents(777007L, 777070L, 1, identity).getTotal());
                assertEquals(ResultCode.RESOURCE_NOT_FOUND, assertThrows(BizException.class,
                    () -> service.preview(777007L, 777070L, 777101L, identity)).getResultCode());
                TenantContext.set("tenant-b");
                assertEquals(ResultCode.RESOURCE_NOT_FOUND, assertThrows(BizException.class,
                    () -> service.preview(777007L, 777070L, 777100L, identity("tenant-b"))).getResultCode());
                TenantContext.set("tenant-a");
                jdbc.update("UPDATE ai_knowledge_document_revision SET acl_mode='RESTRICTED', "
                    + "allowed_subject_types='ADMIN_USER', allowed_subject_ids='[\"99\"]' WHERE id=777101");
                assertEquals(ResultCode.FORBIDDEN, assertThrows(BizException.class,
                    () -> service.preview(777007L, 777070L, 777100L, identity)).getResultCode());
                var denied = service.documents(777007L, 777070L, 1, identity).getList().get(0);
                assertEquals(KnowledgePreviewStatus.FORBIDDEN, denied.status());
                assertNull(denied.title());
                jdbc.update("UPDATE ai_knowledge_document SET deleted=1 WHERE id=777020");
                assertEquals(KnowledgePreviewStatus.UNAVAILABLE,
                    service.documents(777007L, 777070L, 1, identity).getList().get(0).status());
                TenantContext.clear();
                assertThrows(Exception.class, () -> service.preview(777007L, 777070L, 777100L, identity));
            }
        } finally {
            TenantContext.clear();
            dataSource.forceCloseAll();
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    private void seed(JdbcTemplate jdbc) {
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

    private AgentInvocationIdentity identity(String tenant) {
        return new AgentInvocationIdentity(tenant, QuotaSubjectType.ADMIN_USER, "42", true)
            .withChannel(AgentInvocationIdentity.CHANNEL_ADMIN);
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
