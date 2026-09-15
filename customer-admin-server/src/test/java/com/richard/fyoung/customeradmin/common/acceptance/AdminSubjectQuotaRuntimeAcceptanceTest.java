package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.subjectquota.config.SubjectQuotaGatewayProvider;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SubjectQuotaProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.safety.subjectquota.MybatisSubjectQuotaHitStore;
import com.richard.fyoung.customerwork.safety.subjectquota.MybatisSubjectQuotaLevelStore;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectExceedAction;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectLevelResolver;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevel;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevelProvider;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 后台维护的等级经真实数据库进入运行时快照，超限命中再回到同一库。 */
class AdminSubjectQuotaRuntimeAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "quota-runtime-case";
    private static final String LEVEL = "acceptance-runtime";
    private static String database;
    private static JdbcTemplate jdbc;
    private static SubjectQuotaGatewayProvider gateway;
    private static MybatisSubjectQuotaLevelStore levels;
    private static MybatisSubjectQuotaHitStore hits;

    @BeforeAll
    static void createOwnDatabase() throws Exception {
        String candidate = "admin_quota_runtime_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate);
            database = candidate;
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url(database), USER, PASSWORD));
        var properties = new CustomerWorkDbProperties();
        properties.setHost(HOST);
        properties.setPort(PORT);
        properties.setUsername(USER);
        properties.setPassword(PASSWORD);
        properties.setDatabase(database);
        var tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        gateway = new SubjectQuotaGatewayProvider(properties, new AdminCrossDbTenantPlugins(tenant));
        levels = new MybatisSubjectQuotaLevelStore(gateway.get().levelMapper());
        hits = new MybatisSubjectQuotaHitStore(gateway.get().hitMapper());
    }

    @BeforeEach
    void bindTenantAndResetOwnedData() {
        TenantContext.set(TENANT);
        jdbc.update("DELETE FROM cw_subject_quota_level");
        jdbc.update("DELETE FROM cw_subject_quota_hit");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (gateway != null) gateway.close();
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void changedLevelReachesRuntimeAndBlockedRequestPersistsItsTenantAndLimit() throws Exception {
        levels.save(level(1));
        var snapshots = new SubjectQuotaLevelProvider(levels, true);
        var guard = guard(snapshots, hits);
        var subject = QuotaSubject.user("quota-acceptance-user");
        assertTrue(guard.check(subject, "/acceptance/runtime").allowed());
        guard.recordRequest(subject);
        var rejected = guard.check(subject, "/acceptance/runtime");
        assertTrue(rejected.shouldBlock());
        assertEquals(SubjectQuotaDecision.LimitKind.REQUEST, rejected.kind());
        assertEquals(1L, rejected.used());
        assertEquals(1L, rejected.limit());
        awaitPersistedHit();
        assertEquals(TENANT, jdbc.queryForObject("SELECT tenant_id FROM cw_subject_quota_hit", String.class));
        assertEquals(subject.id(), jdbc.queryForObject("SELECT subject_id FROM cw_subject_quota_hit", String.class));
        assertEquals(1L, jdbc.queryForObject("SELECT limit_value FROM cw_subject_quota_hit", Long.class));
        assertEquals("/acceptance/runtime", jdbc.queryForObject("SELECT resource FROM cw_subject_quota_hit", String.class));
        levels.save(level(3));
        snapshots.scheduledRefresh();
        assertEquals(3, guard.usage(subject).requestLimit());
        assertTrue(guard.check(subject, "/acceptance/runtime").allowed());
        // 被拒请求不计入已使用次数，提额后保留此前成功请求的用量。
        assertEquals(1, guard.usage(subject).requestUsed());
    }

    @Test
    void failedDatabaseReloadPreservesLastSnapshotAndRecoversAfterStorageReturns() {
        levels.save(level(1));
        var snapshots = new SubjectQuotaLevelProvider(levels, true);
        var guard = guard(snapshots, null);
        var subject = QuotaSubject.user("quota-recovery-user");
        guard.recordRequest(subject);
        // 只隐藏本测试独占库中的表，模拟真实 SQL 读取失败，结束前恢复原名。
        jdbc.execute("RENAME TABLE cw_subject_quota_level TO acceptance_hidden_level");
        try {
            assertFalse(snapshots.reload());
            assertTrue(guard.check(subject, "/acceptance/recovery").shouldBlock());
            assertEquals(1, guard.usage(subject).requestLimit());
        } finally {
            jdbc.execute("RENAME TABLE acceptance_hidden_level TO cw_subject_quota_level");
        }
        levels.save(level(2));
        assertTrue(snapshots.reload());
        assertTrue(guard.check(subject, "/acceptance/recovery").allowed());
        assertEquals(2, guard.usage(subject).requestLimit());
    }

    private static SubjectQuotaGuard guard(SubjectQuotaLevelProvider snapshots, MybatisSubjectQuotaHitStore hitStore) {
        var properties = new SubjectQuotaProperties();
        properties.setDefaultUserLevel(LEVEL);
        var resolver = new SubjectLevelResolver(snapshots, null, properties);
        return new SubjectQuotaGuard(resolver, new InMemoryWindowCounter(), hitStore, true);
    }

    private static SubjectQuotaLevel level(int requestLimit) {
        return new SubjectQuotaLevel(null, TENANT, LEVEL, "验收等级", QuotaSubjectType.USER,
            60, 0L, requestLimit, SubjectExceedAction.BLOCK, true, "独立数据库验收");
    }

    private static void awaitPersistedHit() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (jdbc.queryForObject("SELECT COUNT(*) FROM cw_subject_quota_hit", Integer.class) == 0
            && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_subject_quota_hit", Integer.class));
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
