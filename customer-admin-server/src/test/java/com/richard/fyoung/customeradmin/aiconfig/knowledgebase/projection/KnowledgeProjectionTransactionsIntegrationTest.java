package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSyncRunMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseVersionService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeDocumentIndexer;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSourceService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSourceSyncCoordinator;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSyncRunRecorder;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.common.mp.MyMetaObjectHandler;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeVersionDO;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 独占真实双库和 Spring 事务代理，验证封锁提交顺序、回滚、行锁等待及当前 ACL。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeProjectionTransactionsIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private String adminDatabase;
    private String customerDatabase;
    private DriverManagerDataSource adminDataSource;
    private DriverManagerDataSource customerDataSource;
    private CrossDbGateway adminGateway;
    private AnnotationConfigApplicationContext context;
    private KnowledgeProjectionGatewayProvider provider;
    private KnowledgeProjectionGateway customerGateway;
    private KnowledgeProjectionService projections;
    private KnowledgeBaseService bases;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate customerJdbc;
    private TransactionTemplate transaction;
    private ExecutorService workers;

    @BeforeAll
    void open() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过知识投影双库事务验证");
        }
        adminDatabase = createDatabase("kb_access_admin_");
        customerDatabase = createDatabase("kb_access_cw_");
        adminDataSource = new DriverManagerDataSource(url(adminDatabase), USER, PASSWORD);
        customerDataSource = new DriverManagerDataSource(url(customerDatabase), USER, PASSWORD);
        Flyway.configure().dataSource(adminDataSource).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        var tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(true);
        var plugins = new AdminCrossDbTenantPlugins(tenantProperties);
        var mapperTypes = List.<Class<?>>of(AiKnowledgeBaseMapper.class, AiKnowledgeBaseVersionMapper.class,
            AiKnowledgeBaseVersionDocumentMapper.class, AiKnowledgeSourceMapper.class,
            AiKnowledgeDocumentMapper.class, AiKnowledgeDocumentRevisionMapper.class,
            AiKnowledgeDocumentChunkMapper.class, AiAgentKnowledgeBaseMapper.class, AiKnowledgeSyncRunMapper.class);
        adminGateway = CrossDbGateways.attach(adminDataSource, "kb-access-admin", mapperTypes, List.of(), plugins.create());
        // Admin 主库真实装配会注册审计填充；测试的手工 factory 也必须使用同一处理器。
        GlobalConfigUtils.getGlobalConfig(adminGateway.sqlSessionFactory().getConfiguration())
            .setMetaObjectHandler(new MyMetaObjectHandler());
        var properties = new CustomerWorkDbProperties();
        properties.setHost(HOST);
        properties.setPort(PORT);
        properties.setDatabase(customerDatabase);
        properties.setUsername(USER);
        properties.setPassword(PASSWORD);
        provider = spy(new KnowledgeProjectionGatewayProvider(properties, plugins));
        TenantContext.set("tenant-a");
        customerGateway = provider.get();
        adminJdbc = new JdbcTemplate(adminDataSource);
        customerJdbc = new JdbcTemplate(customerDataSource);
        var manager = new DataSourceTransactionManager(adminDataSource);
        transaction = new TransactionTemplate(manager);
        context = new AnnotationConfigApplicationContext();
        for (Class<?> mapper : mapperTypes) registerMapper(mapper);
        context.registerBean("knowledgeProjectionGatewayProvider", KnowledgeProjectionGatewayProvider.class, () -> provider);
        context.registerBean(DataSourceTransactionManager.class, () -> manager);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(AesGcmCryptoUtil.class, () -> new AesGcmCryptoUtil("0123456789abcdef"));
        context.registerBean(KnowledgeSearchClient.class, () -> mock(KnowledgeSearchClient.class));
        context.registerBean(AdminRagProperties.class, AdminRagProperties::new);
        context.registerBean(KnowledgeDocumentIndexer.class, () -> mock(KnowledgeDocumentIndexer.class));
        context.register(TxConfig.class, KnowledgeBaseService.class, KnowledgeBaseVersionService.class,
            KnowledgeSourceService.class, KnowledgeSourceSyncCoordinator.class, KnowledgeSyncRunRecorder.class);
        context.scan(getClass().getPackageName());
        context.refresh();
        projections = context.getBean(KnowledgeProjectionService.class);
        bases = context.getBean(KnowledgeBaseService.class);
        workers = Executors.newFixedThreadPool(2);
    }

    @BeforeEach
    void seed() {
        TenantContext.set("tenant-a");
        doReturn(customerGateway).when(provider).get();
        for (String table : List.of("ai_knowledge_base_version_document", "ai_knowledge_document_chunk",
            "ai_knowledge_document_revision", "ai_knowledge_document", "ai_knowledge_sync_run",
            "ai_knowledge_source", "ai_knowledge_base_version", "ai_knowledge_base")) {
            adminJdbc.update("DELETE FROM " + table + " WHERE tenant_id='tenant-a'");
        }
        customerJdbc.update("DELETE FROM cw_knowledge_chunk WHERE tenant_id='tenant-a'");
        customerJdbc.update("DELETE FROM cw_knowledge_version WHERE tenant_id='tenant-a'");
        adminJdbc.update("INSERT INTO ai_knowledge_base(id,tenant_id,kb_name,base_url,app_id,api_key,status) "
            + "VALUES(777007,'tenant-a','Policy','https://example.invalid','test','test',1)");
        adminJdbc.update("UPDATE ai_knowledge_base SET latest_version_no=4,current_version_id=777071 WHERE id=777007");
        adminJdbc.update("INSERT INTO ai_knowledge_base_version(id,tenant_id,knowledge_base_id,version_no,"
            + "base_url,app_id,api_key,snapshot_hash,document_count) VALUES"
            + "(777070,'tenant-a',777007,3,'https://example.invalid','test','test','snapshot-3',1),"
            + "(777071,'tenant-a',777007,4,'https://example.invalid','test','test','snapshot-4',1)");
        adminJdbc.update("INSERT INTO ai_knowledge_source(id,tenant_id,knowledge_base_id,source_code,"
            + "source_name,default_acl_json,status,current_checkpoint) "
            + "VALUES(777010,'tenant-a',777007,'policy','Policy source','{}',1,'cp-0')");
        adminJdbc.update("INSERT INTO ai_knowledge_document(id,tenant_id,knowledge_base_id,source_id,external_id,"
            + "current_revision_id) VALUES(777020,'tenant-a',777007,777010,'refund',777101)");
        adminJdbc.update("INSERT INTO ai_knowledge_document_revision(id,tenant_id,document_id,source_id,"
            + "operation,title,source_version,content,acl_mode,allowed_subject_ids,allowed_channels) VALUES"
            + "(777100,'tenant-a',777020,777010,'UPSERT','Historical policy','source-v1','历史正文','PUBLIC','[]','[]'),"
            + "(777101,'tenant-a',777020,777010,'UPSERT','Current policy','source-v2','当前正文','PUBLIC','[]','[]')");
        adminJdbc.update("INSERT INTO ai_knowledge_base_version_document(tenant_id,knowledge_base_version_id,"
            + "document_revision_id,source_id,external_id) VALUES"
            + "('tenant-a',777070,777100,777010,'refund'),('tenant-a',777071,777100,777010,'refund')");
        adminJdbc.update("INSERT INTO ai_knowledge_document_chunk(tenant_id,document_revision_id,chunk_index,"
            + "content,embedding,dimensions) VALUES('tenant-a',777100,0,'历史片段','[1.0,0.0]',2)");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    void close() throws Exception {
        if (workers != null) workers.shutdownNow();
        if (context != null) context.close();
        if (provider != null) provider.close();
        if (adminGateway != null) adminGateway.close();
        dropDatabase(customerDatabase);
        dropDatabase(adminDatabase);
    }

    @Test
    void customerBlockIsCommittedBeforeAdminChangeAndSurvivesAdminRollback() {
        projectBoth();
        var rollback = new IllegalStateException("rollback after customer fence");
        assertSame(rollback, assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            bases.updateStatus(777007L, 0);
            // customerJdbc 使用另一条连接，必须在 Admin 尚未提交时就看见 BLOCKED。
            assertEquals(2, blockedVersions());
            assertEquals(1, externalAdminStatus());
            throw rollback;
        })));
        assertEquals(1, adminStatus());
        assertEquals(2, blockedVersions());
        projections.project(777007L, 777070L);
        assertEquals("READY", status(777070L));
        assertEquals("BLOCKED", status(777071L), "只恢复显式重投的目标版本");
    }

    @Test
    void customerFenceFailurePreventsAdminCommit() {
        projectBoth();
        var failure = new IllegalStateException("customer fence failed");
        doThrow(failure).when(provider).get();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> bases.updateStatus(777007L, 0)));
        assertEquals(1, adminStatus());
        assertEquals("READY", status(777070L));
    }

    @Test
    void sourceDisableBlocksAllHistoricalVersionsUntilExplicitProjection() {
        projectBoth();
        var request = new KnowledgeSourceSaveRequest("policy", "Policy source", "PUSH", 0, null, null, null);
        context.getBean(KnowledgeSourceService.class).update(777007L, 777010L, request);
        assertEquals(0, adminJdbc.queryForObject("SELECT status FROM ai_knowledge_source WHERE id=777010", Integer.class));
        assertEquals(2, blockedVersions());
        projections.project(777007L, 777070L);
        assertEquals("PRIVATE", customerJdbc.queryForObject(
            "SELECT acl_mode FROM cw_knowledge_chunk WHERE kb_version_id=777070", String.class));
        assertEquals("BLOCKED", status(777071L));
    }

    @Test
    void fullSnapshotTombstoneAndCheckpointCommitOnlyAfterCustomerFence() {
        projectBoth();
        var request = new KnowledgeSyncRequest("sync-empty", "cp-0", "cp-1", true, 0, List.of());
        var source = context.getBean(AiKnowledgeSourceMapper.class).selectById(777010L);
        var run = context.getBean(KnowledgeSyncRunRecorder.class).start(source, request).run();
        context.getBean(KnowledgeSourceSyncCoordinator.class).commit(
            777007L, 777010L, source.getRevision(), run.getId(), request, Map.of());
        assertEquals(2, blockedVersions());
        assertEquals(1, adminJdbc.queryForObject("SELECT deleted FROM ai_knowledge_document WHERE id=777020", Integer.class));
        assertEquals("cp-1", adminJdbc.queryForObject("SELECT current_checkpoint FROM ai_knowledge_source WHERE id=777010", String.class));
        assertEquals("SUCCEEDED", adminJdbc.queryForObject("SELECT status FROM ai_knowledge_sync_run WHERE id=?", String.class, run.getId()));
        projections.project(777007L, 777070L);
        assertEquals("PRIVATE", customerJdbc.queryForObject(
            "SELECT acl_mode FROM cw_knowledge_chunk WHERE kb_version_id=777070", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE ai_knowledge_document_revision SET acl_mode='RESTRICTED' WHERE id=777101",
        "UPDATE ai_knowledge_document_revision SET acl_mode='RESTRICTED' WHERE id=777100",
        "UPDATE ai_knowledge_document SET deleted=1 WHERE id=777020",
        "UPDATE ai_knowledge_source SET status=0 WHERE id=777010",
        "UPDATE ai_knowledge_source SET deleted=1 WHERE id=777010",
        "UPDATE ai_knowledge_base SET status=0 WHERE id=777007",
        "UPDATE ai_knowledge_document SET external_id='different' WHERE id=777020",
        "UPDATE ai_knowledge_document_revision SET document_id=777021 WHERE id=777101",
        "UPDATE ai_knowledge_base_version_document SET source_id=777011 WHERE knowledge_base_version_id=777070"
    })
    void projectionRequiresHistoricalAndCurrentPublicAccessAndValidRelationships(String change) {
        adminJdbc.update(change);
        assertEquals(1, projections.project(777007L, 777070L));
        assertEquals("PRIVATE", customerJdbc.queryForObject(
            "SELECT acl_mode FROM cw_knowledge_chunk WHERE kb_version_id=777070", String.class));
    }

    @Test
    void historicalTitleVersionAndStableIdSurviveReprojection() {
        projectBoth();
        Long before = customerJdbc.queryForObject("SELECT id FROM cw_knowledge_chunk WHERE kb_version_id=777070", Long.class);
        projections.project(777007L, 777070L);
        assertEquals(before, customerJdbc.queryForObject("SELECT id FROM cw_knowledge_chunk WHERE kb_version_id=777070", Long.class));
        assertEquals("Historical policy", customerJdbc.queryForObject(
            "SELECT document_title FROM cw_knowledge_chunk WHERE kb_version_id=777070", String.class));
        assertEquals("source-v1", customerJdbc.queryForObject(
            "SELECT source_version FROM cw_knowledge_chunk WHERE kb_version_id=777070", String.class));
        assertEquals(3, customerJdbc.queryForObject("SELECT version_no FROM cw_knowledge_version WHERE kb_version_id=777070", Integer.class));
    }

    @Test
    void projectKeepsKnowledgeBaseLockUntilReadyThenConcurrentDisableWins() throws Exception {
        projections.project(777007L, 777070L);
        var beforeReady = new CountDownLatch(1);
        var allowReady = new CountDownLatch(1);
        var versionMapper = spy(customerGateway.versionMapper());
        doAnswer(invocation -> {
            beforeReady.countDown();
            await(allowReady);
            return customerGateway.versionMapper().updateById(invocation.<KnowledgeVersionDO>getArgument(0));
        }).when(versionMapper).updateById(any(KnowledgeVersionDO.class));
        doReturn(new KnowledgeProjectionGateway(customerGateway.chunkMapper(), versionMapper)).when(provider).get();
        Future<?> project = submit(() -> projections.project(777007L, 777070L));
        await(beforeReady);
        Future<?> disable = submit(() -> bases.updateStatus(777007L, 0));
        try {
            awaitTableLockWait("ai_knowledge_base");
            assertFalse(disable.isDone(), "撤权必须等待投影持有的同一 KB 行锁");
            assertEquals("BLOCKED", status(777070L));
        } finally {
            allowReady.countDown();
            project.get(10, TimeUnit.SECONDS);
            disable.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, adminStatus());
        assertEquals("BLOCKED", status(777070L));
    }

    @Test
    void projectWaitingForRevocationReadsCommittedCurrentPermission() throws Exception {
        projections.project(777007L, 777070L);
        var revoked = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        Future<?> disable = submit(() -> transaction.executeWithoutResult(status -> {
            bases.updateStatus(777007L, 0);
            revoked.countDown();
            await(allowCommit);
        }));
        await(revoked);
        Future<?> project = submit(() -> projections.project(777007L, 777070L));
        try {
            awaitTableLockWait("ai_knowledge_base");
            assertFalse(project.isDone());
        } finally {
            allowCommit.countDown();
            disable.get(10, TimeUnit.SECONDS);
            project.get(10, TimeUnit.SECONDS);
        }
        assertEquals("PRIVATE", customerJdbc.queryForObject(
            "SELECT acl_mode FROM cw_knowledge_chunk WHERE kb_version_id=777070", String.class));
    }

    @Test
    void interruptedProjectionRemainsBlockedUntilCompleteRetry() {
        projectBoth();
        Long original = customerJdbc.queryForObject("SELECT id FROM cw_knowledge_chunk WHERE kb_version_id=777070", Long.class);
        var chunks = spy(customerGateway.chunkMapper());
        var failure = new IllegalStateException("chunk write failed");
        doThrow(failure).when(chunks).upsertProjection(any());
        doReturn(new KnowledgeProjectionGateway(chunks, customerGateway.versionMapper())).when(provider).get();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> projections.project(777007L, 777070L)));
        assertEquals("BLOCKED", status(777070L));
        assertEquals("READY", status(777071L));
        doReturn(customerGateway).when(provider).get();
        projections.project(777007L, 777070L);
        assertEquals("READY", status(777070L));
        assertEquals(original, customerJdbc.queryForObject("SELECT id FROM cw_knowledge_chunk WHERE kb_version_id=777070", Long.class));
    }

    @Test
    void syncLocksKnowledgeBaseBeforeWaitingForSourceSoProjectionCannotOvertake() throws Exception {
        projections.project(777007L, 777070L);
        var request = new KnowledgeSyncRequest("sync-locked", "cp-0", "cp-1", true, 0, List.of());
        var source = context.getBean(AiKnowledgeSourceMapper.class).selectById(777010L);
        var run = context.getBean(KnowledgeSyncRunRecorder.class).start(source, request).run();
        try (var connection = DriverManager.getConnection(url(adminDatabase), USER, PASSWORD)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 var locked = statement.executeQuery("SELECT id FROM ai_knowledge_source WHERE id=777010 FOR UPDATE")) {
                assertTrue(locked.next());
            }
            Future<?> sync = submit(() -> context.getBean(KnowledgeSourceSyncCoordinator.class).commit(
                777007L, 777010L, source.getRevision(), run.getId(), request, Map.of()));
            awaitTableLockWait("ai_knowledge_source");
            Future<?> project = submit(() -> projections.project(777007L, 777070L));
            try {
                awaitTableLockWait("ai_knowledge_base");
                assertFalse(project.isDone(), "同步等待源锁期间必须已持有 KB 锁，投影不能越过同步");
            } finally {
                connection.rollback();
                sync.get(10, TimeUnit.SECONDS);
                project.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals("PRIVATE", customerJdbc.queryForObject(
            "SELECT acl_mode FROM cw_knowledge_chunk WHERE kb_version_id=777070", String.class));
    }

    private void projectBoth() {
        projections.project(777007L, 777070L);
        projections.project(777007L, 777071L);
    }

    private int blockedVersions() {
        return customerJdbc.queryForObject("SELECT COUNT(*) FROM cw_knowledge_version "
            + "WHERE tenant_id='tenant-a' AND kb_code='777007' AND access_status='BLOCKED'", Integer.class);
    }

    private String status(long versionId) {
        return customerJdbc.queryForObject("SELECT access_status FROM cw_knowledge_version WHERE kb_version_id=?", String.class, versionId);
    }

    private int adminStatus() {
        return adminJdbc.queryForObject("SELECT status FROM ai_knowledge_base WHERE id=777007", Integer.class);
    }

    private int externalAdminStatus() {
        try (var connection = DriverManager.getConnection(url(adminDatabase), USER, PASSWORD);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT status FROM ai_knowledge_base WHERE id=777007")) {
            assertTrue(result.next());
            return result.getInt(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 等待数据库证明实际锁竞争，超时仅作为失败上限，不以 sleep 推断线程时序。 */
    private void awaitTableLockWait(String table) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waits = customerJdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                + "WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?", Integer.class, adminDatabase, table);
            if (waits != null && waits > 0) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("未观察到真实行锁等待：" + table);
    }

    private Future<?> submit(Runnable operation) {
        return workers.submit(() -> {
            TenantContext.set("tenant-a");
            try { operation.run(); } finally { TenantContext.clear(); }
        });
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "并发屏障超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private <T> void registerMapper(Class<T> type) {
        context.registerBean(type, () -> adminGateway.getMapper(type));
    }

    private String createDatabase(String prefix) throws Exception {
        String candidate = prefix + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + candidate); }
        return candidate;
    }

    private void dropDatabase(String database) throws Exception {
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TxConfig { }
}
