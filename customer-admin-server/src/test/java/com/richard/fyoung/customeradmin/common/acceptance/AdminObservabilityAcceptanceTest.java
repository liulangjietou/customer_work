package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitLogPageQuery;
import com.richard.fyoung.customeradmin.contentguard.service.SensitiveWordHitLogService;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AgentCallStatsStoreConfig;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AppAgentCallStatsGatewayProvider;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsQuery;
import com.richard.fyoung.customeradmin.workspace.callstats.service.AgentCallStatsService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 调用统计与命中看板的双库事实：生产迁移、精确租户、来源路由及删除边界。 */
class AdminObservabilityAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "observability-case";
    private static final long CALL_ID = 801L;
    private static final long FOREIGN_ID = 802L;
    private static final long CASE_VARIANT_ID = 803L;
    private static final long START_MS = 1789441200000L;
    private static final List<String> OWN_DATABASES = new ArrayList<>();
    private static JdbcTemplate admin;
    private static JdbcTemplate customer;
    private static AppAgentCallStatsGatewayProvider appProvider;
    private static ContentGuardGatewayProvider contentGuard;
    private static AgentCallStatsService calls;
    private static SensitiveWordHitLogService hits;

    @BeforeAll
    static void initializeOwnDatabases() throws Exception {
        String adminDatabase = createDatabase();
        String customerDatabase = createDatabase();
        var adminSource = new DriverManagerDataSource(url(adminDatabase), USER, PASSWORD);
        admin = new JdbcTemplate(adminSource);
        customer = new JdbcTemplate(new DriverManagerDataSource(url(customerDatabase), USER, PASSWORD));
        Flyway.configure().dataSource(adminSource).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        var properties = new AdminTenantProperties();
        properties.setEnabled(true);
        var plugins = new AdminCrossDbTenantPlugins(properties);
        var connection = new CustomerWorkDbProperties();
        connection.setHost(HOST);
        connection.setPort(PORT);
        connection.setUsername(USER);
        connection.setPassword(PASSWORD);
        connection.setDatabase(customerDatabase);
        // 客服库由现有门面执行权威迁移，随后以调用统计的只读门面查询。
        contentGuard = new ContentGuardGatewayProvider(connection, plugins);
        contentGuard.get();
        appProvider = new AppAgentCallStatsGatewayProvider(connection, plugins);
        calls = new AgentCallStatsService(
            new AgentCallStatsStoreConfig().adminAgentCallStatsGateway(adminSource, plugins), appProvider);
        hits = new SensitiveWordHitLogService(contentGuard);
    }

    @BeforeEach
    void seedOnlyOwnDatabases() {
        TenantContext.set(TENANT);
        for (var database : List.of(admin, customer)) {
            database.update("DELETE FROM cw_agent_call_segment");
            database.update("DELETE FROM cw_agent_call_log");
            seedCall(database, CALL_ID, TENANT, database == admin ? "admin-answer" : "app-answer");
            seedCall(database, FOREIGN_ID, "foreign-tenant", "foreign-answer");
            seedCall(database, CASE_VARIANT_ID, "OBSERVABILITY-CASE", "case-variant-answer");
            seedSegment(database, 1801L, CALL_ID, TENANT, "own-segment");
            seedSegment(database, 1802L, FOREIGN_ID, "foreign-tenant", "foreign-segment");
            // 即使异常历史关联指向本租户主记录，其他租户分段也不能被读出或级联删除。
            seedSegment(database, 1803L, CALL_ID, "OBSERVABILITY-CASE", "case-variant-segment");
        }
        customer.update("DELETE FROM cw_sensitive_word_hit_log");
        seedHit(901L, TENANT, "INBOUND", "own-term");
        seedHit(902L, "foreign-tenant", "INBOUND", "foreign-term");
        seedHit(903L, "OBSERVABILITY-CASE", "INBOUND", "variant-term");
        seedHit(904L, TENANT, "OUTBOUND", "outbound-term");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @AfterAll
    static void removeOwnDatabases() throws Exception {
        if (appProvider != null) appProvider.close();
        if (contentGuard != null) contentGuard.close();
        for (String database : OWN_DATABASES) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void bothSourcesKeepExactTenantAcrossPageSummaryTrendAndDetail() {
        for (String source : List.of("ADMIN", "APP")) {
            var query = new AgentCallStatsQuery();
            query.setSource(source);
            query.setPageNum(1);
            query.setPageSize(10);
            var page = calls.page(query);
            assertEquals(1L, page.getTotal());
            assertEquals(List.of(CALL_ID), page.getRows().stream().map(row -> row.getId()).toList());
            assertEquals(1L, calls.summary(query).getTotalCalls());
            assertEquals(1L, calls.trend(query).stream().mapToLong(row -> row.getCount()).sum());
            var detail = calls.detail(CALL_ID, source);
            assertEquals(source.equals("ADMIN") ? "admin-answer" : "app-answer", detail.getAnswer());
            assertEquals(List.of("own-segment"), detail.getSegments().stream().map(row -> row.getName()).toList());
            assertThrows(BizException.class, () -> calls.detail(FOREIGN_ID, source));
            assertThrows(BizException.class, () -> calls.detail(CASE_VARIANT_ID, source));
        }
    }

    @Test
    void adminDeletionPreservesForeignSegmentsAndCustomerDatabase() {
        assertTrue(calls.delete(CALL_ID, "ADMIN"));
        assertFalse(calls.delete(CALL_ID, "ADMIN"));
        assertFalse(calls.delete(FOREIGN_ID, "ADMIN"));
        assertFalse(calls.delete(CASE_VARIANT_ID, "ADMIN"));
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM cw_agent_call_segment WHERE id=1801", Integer.class));
        assertEquals(2, admin.queryForObject("SELECT COUNT(*) FROM cw_agent_call_segment WHERE id IN (1802,1803)", Integer.class));
        assertEquals(2, admin.queryForObject("SELECT COUNT(*) FROM cw_agent_call_log", Integer.class));
        assertThrows(BizException.class, () -> calls.delete(CALL_ID, "APP"));
        assertEquals(3, customer.queryForObject("SELECT COUNT(*) FROM cw_agent_call_log", Integer.class));
    }

    @Test
    void replayManifestBindsSourceAndCannotExposeForeignCapture() {
        for (String source : List.of("ADMIN", "APP")) {
            var manifest = calls.replayManifest(CALL_ID, source);
            assertEquals(source, manifest.source());
            assertEquals(CALL_ID, manifest.callLogId());
            assertEquals(source.equals("ADMIN") ? "admin-answer" : "app-answer", manifest.recordedAnswer());
            assertEquals(1, manifest.segments().size());
            assertThrows(BizException.class, () -> calls.replayManifest(CASE_VARIANT_ID, source));
        }
    }

    @Test
    void hitLogSummaryAndRowsShareDirectionAndExactTenantBoundary() {
        var query = new SensitiveWordHitLogPageQuery();
        query.setDirection("INBOUND");
        query.setPageNum(1);
        query.setPageSize(10);
        assertEquals(1L, hits.page(query).getTotal());
        assertEquals(List.of("own-term"), hits.page(query).getList().get(0).getWords());
        var stats = hits.stats(query);
        assertEquals(1L, stats.getTotal());
        assertEquals(List.of("own-term"), stats.getTopWords().stream().map(row -> row.getLabel()).toList());
        assertEquals(1L, stats.getTrend().stream().mapToLong(row -> row.getTotal()).sum());
        query.setSessionId("missing-session");
        assertTrue(hits.page(query).getList().isEmpty());
        assertEquals(0L, hits.stats(query).getTotal());
    }

    private static void seedCall(JdbcTemplate jdbc, long id, String tenant, String answer) {
        jdbc.update("INSERT INTO cw_agent_call_log(id,tenant_id,request_id,session_id,agent_code,question,answer,start_time,end_time,duration_ms) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?)", id, tenant, "request-" + id, "session-" + id, "acceptance-agent", "验收问题", answer,
            START_MS, START_MS + 12, 12);
    }

    private static void seedSegment(JdbcTemplate jdbc, long id, long callId, String tenant, String name) {
        jdbc.update("INSERT INTO cw_agent_call_segment(id,tenant_id,call_log_id,seq,kind,name,start_time,duration_ms) "
            + "VALUES (?,?,?,1,'MODEL',?,?,12)", id, tenant, callId, name, START_MS);
    }

    private static void seedHit(long id, String tenant, String direction, String words) {
        customer.update("INSERT INTO cw_sensitive_word_hit_log(id,tenant_id,direction,action,words,hit_count,session_id,created_at_ms) "
            + "VALUES (?,?,?,'BLOCK',?,1,'acceptance-session',?)", id, tenant, direction, words, START_MS);
    }

    private static String createDatabase() throws Exception {
        String database = "admin_observe_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        OWN_DATABASES.add(database);
        return database;
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
