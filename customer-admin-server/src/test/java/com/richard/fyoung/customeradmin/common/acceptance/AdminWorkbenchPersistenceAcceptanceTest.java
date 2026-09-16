package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.mp.MyMetaObjectHandler;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.config.MybatisPlusConfig;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.datascope.DataScopeContext;
import com.richard.fyoung.customeradmin.datascope.DataScopeProperties;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workbench.controller.WorkbenchAgentController;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteSaveRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreateRequest;
import com.richard.fyoung.customeradmin.workbench.mapper.WorkbenchSiteMapper;
import com.richard.fyoung.customeradmin.workbench.mapper.WorkbenchTokenMapper;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchSiteService;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchTokenService;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchUserscriptGenerator;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** 工作台实际持久化与脚本凭证读取；只使用本测试创建的独立数据库。 */
class AdminWorkbenchPersistenceAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final String PORT = System.getenv().getOrDefault("MYSQL_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "workbench-case";
    private static final String FOREIGN_TENANT = "WORKBENCH-CASE";
    private static final long OWNER = 7L;
    private static final long OTHER_USER = 8L;
    private static final String SITE_SECRET = "fixture-site-secret-2048";
    private static String database;
    private static JdbcTemplate jdbc;
    private static WorkbenchSiteService sites;
    private static WorkbenchTokenService tokens;
    private static WorkbenchAgentController agent;

    @BeforeAll
    static void createOwnDatabase() throws Exception {
        String candidate = "admin_workbench_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate);
            database = candidate;
        }
        var source = new DriverManagerDataSource(url(database), USER, PASSWORD);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(WorkbenchSiteMapper.class, WorkbenchTokenMapper.class, OperationLogMapper.class)) {
            configuration.addMapper(mapper);
        }
        var tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenant, new DataScopeProperties()));
        var sessionFactory = factory.getObject();
        GlobalConfigUtils.getGlobalConfig(sessionFactory.getConfiguration()).setMetaObjectHandler(new MyMetaObjectHandler());
        var session = new SqlSessionTemplate(sessionFactory);
        sites = new WorkbenchSiteService(session.getMapper(WorkbenchSiteMapper.class),
            new AesGcmCryptoUtil("0123456789abcdef0123456789abcdef"));
        tokens = new WorkbenchTokenService(session.getMapper(WorkbenchTokenMapper.class));
        agent = new WorkbenchAgentController(tokens, sites, session.getMapper(OperationLogMapper.class));
    }

    @BeforeEach
    void resetOwnedData() {
        TenantContext.set(TENANT);
        DataScopeContext.set(DataScope.TENANT, OWNER);
        for (String table : List.of("workbench_site", "workbench_token", "sys_operation_log")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void sitePasswordIsEncryptedMaskedAndPreservedByAnEmptyPasswordEdit() {
        long id = createSite("own-site", "own.example.invalid", OWNER, true);
        String cipher = jdbc.queryForObject("SELECT password FROM workbench_site WHERE id=?", String.class, id);
        assertNotEquals(SITE_SECRET, cipher);
        assertEquals(SITE_SECRET, sites.getSecret(id));
        var listed = sites.page(new PageQuery()).getList();
        assertEquals(1, listed.size());
        assertEquals("********************2048", listed.get(0).getPasswordMasked());
        assertTrue(listed.get(0).getHasPassword());
        sites.update(id, siteRequest("edited-site", "own.example.invalid", "", true));
        assertEquals(cipher, jdbc.queryForObject("SELECT password FROM workbench_site WHERE id=?", String.class, id));
        assertEquals("edited-site", sites.page(new PageQuery()).getList().get(0).getName());
        assertEquals(SITE_SECRET, sites.getSecret(id));
        sites.delete(id);
        assertEquals(0, sites.page(new PageQuery()).getTotal());
        assertThrows(BizException.class, () -> sites.getSecret(id));
        assertEquals(1, jdbc.queryForObject("SELECT deleted FROM workbench_site WHERE id=?", Integer.class, id));
    }

    @Test
    void exactTenantAndSelfScopeProtectCredentialsAndWrites() {
        long own = createSite("own-site", "own.example.invalid", OWNER, true);
        long other = createSite("other-user-site", "other.example.invalid", OTHER_USER, true);
        long foreign = TenantContext.callWith(FOREIGN_TENANT,
            () -> createSite("foreign-site", "foreign.example.invalid", OWNER, true));
        assertEquals(2, sites.page(new PageQuery()).getTotal());
        assertThrows(BizException.class, () -> sites.getSecret(foreign));
        assertThrows(BizException.class, () -> sites.delete(foreign));
        DataScopeContext.set(DataScope.SELF, OWNER);
        assertEquals(List.of(own), sites.page(new PageQuery()).getList().stream().map(v -> v.getId()).toList());
        assertThrows(BizException.class, () -> sites.getSecret(other));
        assertThrows(BizException.class,
            () -> sites.update(other, siteRequest("forbidden-edit", "other.example.invalid", "", true)));
        sites.delete(own);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM workbench_site WHERE deleted=0", Integer.class));
        assertEquals("other-user-site", jdbc.queryForObject("SELECT name FROM workbench_site WHERE id=?", String.class, other));
    }

    @Test
    void tokenHashOwnershipRevocationAndExpiryAreEnforcedAgainstStoredRows() throws Exception {
        var created = tokens.createToken(OWNER, new WorkbenchTokenCreateRequest("acceptance", 1));
        String raw = created.getToken();
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedHash, jdbc.queryForObject("SELECT token_hash FROM workbench_token WHERE id=?", String.class, created.getId()));
        assertEquals(TENANT, jdbc.queryForObject("SELECT tenant_id FROM workbench_token WHERE id=?", String.class, created.getId()));
        assertTrue(tokens.listByUser(OTHER_USER).isEmpty());
        assertThrows(BizException.class, () -> tokens.revoke(OTHER_USER, created.getId()));
        TenantContext.runWith(FOREIGN_TENANT, () -> {
            assertTrue(tokens.listByUser(OWNER).isEmpty());
            assertThrows(BizException.class, () -> tokens.revoke(OWNER, created.getId()));
        });
        TenantContext.clear();
        DataScopeContext.clear();
        assertEquals(new WorkbenchTokenService.Principal(OWNER, TENANT), tokens.validate(raw));
        assertNotNull(jdbc.queryForObject("SELECT last_used_time FROM workbench_token WHERE id=?", java.sql.Timestamp.class, created.getId()));
        assertNull(DataScopeContext.currentScope());
        TenantContext.set(TENANT);
        DataScopeContext.set(DataScope.TENANT, OWNER);
        tokens.revoke(OWNER, created.getId());
        assertTrue(tokens.listByUser(OWNER).get(0).getRevoked());
        assertThrows(BizException.class, () -> tokens.validate(raw));
        var expired = tokens.createToken(OWNER, new WorkbenchTokenCreateRequest("expired", 1));
        jdbc.update("UPDATE workbench_token SET expire_time=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id=?", expired.getId());
        assertThrows(BizException.class, () -> tokens.validate(expired.getToken()));
    }

    @Test
    void scriptReadsOnlyTheTokenOwnersSiteAndPersistsAnAuditWithoutThePassword() {
        createSite("own-site", "own.example.invalid", OWNER, true);
        createSite("other-user-site", "other.example.invalid", OTHER_USER, true);
        createSite("disabled-site", "disabled.example.invalid", OWNER, false);
        TenantContext.runWith(FOREIGN_TENANT,
            () -> createSite("foreign-site", "foreign.example.invalid", OWNER, true));
        var created = tokens.createToken(OWNER, new WorkbenchTokenCreateRequest("script", 1));
        DataScopeContext.set(DataScope.SELF, OWNER);
        String script = new WorkbenchUserscriptGenerator("https://admin.example.invalid")
            .generate(created.getToken(), sites.listEnabledHosts());
        assertTrue(script.contains(created.getToken()));
        assertTrue(script.contains("@match        *://own.example.invalid/*"));
        for (String absent : List.of("other.example.invalid", "foreign.example.invalid", "disabled.example.invalid", SITE_SECRET)) {
            assertFalse(script.contains(absent), absent);
        }
        TenantContext.clear();
        DataScopeContext.clear();
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var result = agent.site("own.example.invalid", created.getToken(), request).getData();
        assertEquals(SITE_SECRET, result.getPassword());
        for (String denied : List.of("other.example.invalid", "foreign.example.invalid", "disabled.example.invalid")) {
            assertThrows(BizException.class, () -> agent.site(denied, created.getToken(), request));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Integer.class));
        assertEquals(TENANT, jdbc.queryForObject("SELECT tenant_id FROM sys_operation_log", String.class));
        assertEquals(OWNER, jdbc.queryForObject("SELECT user_id FROM sys_operation_log", Long.class));
        String audit = jdbc.queryForObject("SELECT operation FROM sys_operation_log", String.class);
        assertTrue(audit.contains("own.example.invalid"));
        assertFalse(audit.contains(SITE_SECRET));
        assertFalse(audit.contains(created.getToken()));
        assertNull(DataScopeContext.currentScope());
    }

    private static long createSite(String name, String host, long owner, boolean enabled) {
        sites.create(siteRequest(name, host, SITE_SECRET, enabled));
        long id = jdbc.queryForObject("SELECT id FROM workbench_site WHERE name=?", Long.class, name);
        // 本测试直接调用 Service，没有登录请求上下文；显式设置归属夹具后再验证真实数据权限插件。
        jdbc.update("UPDATE workbench_site SET create_by=? WHERE id=?", owner, id);
        return id;
    }

    private static WorkbenchSiteSaveRequest siteRequest(String name, String host, String password, boolean enabled) {
        return new WorkbenchSiteSaveRequest(name, "acceptance", "https://" + host + "/login", "fixture-account",
            password, "验收数据", enabled, "#account", "#password", "#submit", "auto", "click", 0, 0);
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }
}
