package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.dto.SystemToolSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.service.SystemToolService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.config.MybatisPlusConfig;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.RateLimitRuleSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordPageQuery;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.service.RateLimitRuleService;
import com.richard.fyoung.customeradmin.contentguard.service.SensitiveWordService;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.datascope.DataScopeContext;
import com.richard.fyoung.customeradmin.datascope.DataScopeProperties;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.dto.RoleSaveRequest;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.system.role.service.RoleService;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** UI 行启停之外的真实写入证据：独立双库、权威迁移、生产租户与数据范围插件。 */
class AdminRowMutationAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final long TOOL_ID = 960001L;
    private static final long AGENT_ID = 970001L;
    private static final long ROLE_ID = 980001L;
    private static final long PERMISSION_ID = 990001L;
    private static final List<String> OWN_DATABASES = new ArrayList<>();
    private static DriverManagerDataSource adminSource;
    private static JdbcTemplate adminJdbc;
    private static JdbcTemplate customerJdbc;
    private static ContentGuardGatewayProvider contentGuard;
    private static AdminTenantProperties tenantProperties;

    @BeforeAll
    static void createOwnDatabases() throws Exception {
        try (var socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 500); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，无法核实行操作落库"); }
        String admin = createDatabase();
        String customer = createDatabase();
        adminSource = new DriverManagerDataSource(url(admin), USER, PASSWORD);
        adminJdbc = new JdbcTemplate(adminSource);
        customerJdbc = new JdbcTemplate(new DriverManagerDataSource(url(customer), USER, PASSWORD));
        Flyway.configure().dataSource(adminSource).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(true);
        var connection = new CustomerWorkDbProperties();
        connection.setHost(HOST);
        connection.setPort(PORT);
        connection.setUsername(USER);
        connection.setPassword(PASSWORD);
        connection.setDatabase(customer);
        contentGuard = new ContentGuardGatewayProvider(connection, new AdminCrossDbTenantPlugins(tenantProperties));
        contentGuard.get();
    }

    @BeforeEach
    void bindOperator() {
        TenantContext.set("row-acceptance");
        DataScopeContext.set(DataScope.TENANT, 7L);
    }

    @AfterEach
    void clearOperator() { TenantContext.clear(); DataScopeContext.clear(); }

    @AfterAll
    static void removeOwnDatabases() throws Exception {
        if (contentGuard != null) contentGuard.close();
        for (String database : OWN_DATABASES) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
        }
    }

    @Test
    void toolUpdatePersistsCatalogFieldsAndInvalidatesItsReferencingAgent() throws Exception {
        // 系统工具是全局代码目录，没有 tenant_id；关联与智能体仍由生产租户插件约束。
        adminJdbc.update("INSERT INTO ai_system_tool(id,tool_code,tool_name,enabled) VALUES (?,?,?,1)",
            TOOL_ID, "acceptance-tool", "验收工具");
        adminJdbc.update("INSERT INTO ai_agent(id,tenant_id,agent_name,agent_code,model_id,status) "
            + "VALUES (?,'row-acceptance','验收智能体','acceptance-agent',1,1)", AGENT_ID);
        adminJdbc.update("INSERT INTO ai_agent_system_tool(agent_id,system_tool_id,tenant_id) "
            + "VALUES (?,?,'row-acceptance')", AGENT_ID, TOOL_ID);
        var template = adminTemplate();
        var cache = mock(AgentInstanceCache.class);
        var service = new SystemToolService(template.getMapper(AiSystemToolMapper.class),
            template.getMapper(AiAgentSystemToolMapper.class), template.getMapper(AiAgentMapper.class), cache);
        service.update(TOOL_ID, new SystemToolSaveRequest("验收工具新名称", "保留中文描述", 0, "已核对"));
        var actual = service.get(TOOL_ID);
        assertEquals(0, actual.getEnabled());
        assertEquals("acceptance-tool", actual.getToolCode());
        assertEquals("验收工具新名称", actual.getToolName());
        assertEquals("保留中文描述", actual.getDescription());
        assertEquals("已核对", actual.getRemark());
        verify(cache).invalidateAll(List.of("acceptance-agent"));
        assertEquals(0, adminJdbc.queryForObject("SELECT enabled FROM ai_system_tool WHERE id=?", Integer.class, TOOL_ID));
    }

    @Test
    void ruleToggleChangesOnlyOwnedStateAndTimestampAndCanBeReenabled() {
        var service = new RateLimitRuleService(contentGuard);
        var request = new RateLimitRuleSaveRequest();
        request.setRuleName("acceptance-limit");
        request.setPathPrefix("/api/customer/");
        request.setDimension("GLOBAL");
        request.setLimitCount(60);
        request.setAlgorithm("FIXED_WINDOW");
        request.setWindowSeconds(60);
        request.setPriority(10);
        request.setEnabled(true);
        service.create(request);
        long ownId = service.page(new PageQuery()).getList().get(0).getId();
        TenantContext.runWith("other-tenant", () -> service.create(request));
        long foreignId = TenantContext.callWith("other-tenant", () -> service.page(new PageQuery()).getList().get(0).getId());
        customerJdbc.update("UPDATE cw_rate_limit_rule SET updated_at_ms=1 WHERE id=?", ownId);
        service.toggle(ownId, false);
        assertFalse(service.get(ownId).getEnabled());
        assertEquals("/api/customer/", service.get(ownId).getPathPrefix());
        assertEquals(60, service.get(ownId).getLimitCount());
        assertTrue(service.get(ownId).getUpdatedAtMs() > 1);
        assertThrows(BizException.class, () -> service.toggle(foreignId, false));
        assertEquals(1, customerJdbc.queryForObject("SELECT enabled FROM cw_rate_limit_rule WHERE id=?", Integer.class, foreignId));
        service.toggle(ownId, true);
        assertTrue(service.get(ownId).getEnabled());
        assertEquals(1L, service.page(new PageQuery()).getTotal());
    }

    @Test
    void globalSensitiveWordTogglePreservesRuleAndRefreshesRuntimeFingerprint() {
        // 敏感词库是全局资源，控制面写权限由 Controller 另测；这里核对真实运行库字段。
        var service = new SensitiveWordService(contentGuard);
        var request = new SensitiveWordSaveRequest();
        request.setWord("验收词条");
        request.setCategory("CUSTOM");
        request.setAction("MASK");
        request.setEnabled(true);
        service.create(request);
        var query = new SensitiveWordPageQuery();
        query.setKeyword("验收词条");
        long id = service.page(query).getList().get(0).getId();
        customerJdbc.update("UPDATE cw_sensitive_word SET updated_at_ms=1 WHERE id=?", id);

        service.toggle(id, false);

        var actual = service.get(id);
        assertFalse(actual.getEnabled());
        assertEquals("验收词条", actual.getWord());
        assertEquals("CUSTOM", actual.getCategory());
        assertEquals("MASK", actual.getAction());
        assertTrue(actual.getUpdatedAtMs() > 1);
        assertEquals(0, customerJdbc.queryForObject("SELECT enabled FROM cw_sensitive_word WHERE id=?", Integer.class, id));
        service.toggle(id, true);
        assertTrue(service.get(id).getEnabled());
        assertEquals(1L, service.page(query).getTotal());
    }

    @Test
    void roleSavePersistsExplicitPermissionsAndCannotUpdateAnotherTenantRole() throws Exception {
        adminJdbc.update("INSERT INTO sys_permission(id,perm_name,perm_code,type) VALUES (?,'验收读取','acceptance:read',2)", PERMISSION_ID);
        adminJdbc.update("INSERT INTO sys_role(id,role_name,role_code,tenant_id,data_scope) "
            + "VALUES (?,'验收角色','acceptance-role','row-acceptance','TENANT')", ROLE_ID);
        adminJdbc.update("INSERT INTO sys_role(id,role_name,role_code,tenant_id,data_scope) "
            + "VALUES (?,'其他租户角色','acceptance-role','other-tenant','TENANT')", ROLE_ID + 1);
        var template = adminTemplate();
        var service = new RoleService(template.getMapper(SysRoleMapper.class),
            template.getMapper(SysRolePermissionMapper.class), template.getMapper(SysPermissionMapper.class),
            template.getMapper(SysUserRoleMapper.class), mock(CrossTenantAuthority.class));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(adminSource));
        var request = new RoleSaveRequest("服务角色", "acceptance-role", "已核对授权", 1, "TENANT", List.of(PERMISSION_ID));

        transaction.executeWithoutResult(status -> service.update(ROLE_ID, request));

        assertEquals("服务角色", service.get(ROLE_ID).getRoleName());
        assertEquals(List.of(PERMISSION_ID), service.get(ROLE_ID).getPermissionIds());
        assertEquals("row-acceptance", adminJdbc.queryForObject(
            "SELECT tenant_id FROM sys_role_permission WHERE role_id=? AND permission_id=?", String.class, ROLE_ID, PERMISSION_ID));
        assertThrows(BizException.class, () -> transaction.executeWithoutResult(status -> service.update(ROLE_ID + 1, request)));
        assertEquals("其他租户角色", adminJdbc.queryForObject("SELECT role_name FROM sys_role WHERE id=?", String.class, ROLE_ID + 1));
    }

    private static SqlSessionTemplate adminTemplate() throws Exception {
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(adminSource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiSystemToolMapper.class);
        configuration.addMapper(AiAgentSystemToolMapper.class);
        configuration.addMapper(AiAgentMapper.class);
        configuration.addMapper(SysRoleMapper.class);
        configuration.addMapper(SysRolePermissionMapper.class);
        configuration.addMapper(SysPermissionMapper.class);
        configuration.addMapper(SysUserRoleMapper.class);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenantProperties, new DataScopeProperties()));
        return new SqlSessionTemplate(factory.getObject());
    }

    private static String createDatabase() throws Exception {
        String database = "admin_rows_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + database); }
        OWN_DATABASES.add(database);
        return database;
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
