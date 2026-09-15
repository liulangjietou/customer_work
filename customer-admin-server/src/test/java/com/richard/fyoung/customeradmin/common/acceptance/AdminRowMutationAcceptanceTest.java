package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto.ChannelRobotSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.service.ChannelRobotService;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskRunMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.scheduler.ScheduledTaskChangedEvent;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service.ScheduledTaskService;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.dto.SystemToolSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.service.SystemToolService;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.common.mp.MyMetaObjectHandler;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.config.AdminScheduledTaskProperties;
import com.richard.fyoung.customeradmin.config.AdminSchedulerProperties;
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
import com.richard.fyoung.customeradmin.tenant.access.TenantChannelDisableService;
import com.richard.fyoung.customeradmin.tenant.access.service.TenantAccessPublishTaskService;
import com.richard.fyoung.customeradmin.tenant.dto.TenantSaveRequest;
import com.richard.fyoung.customeradmin.tenant.mapper.SysTenantMapper;
import com.richard.fyoung.customeradmin.tenant.service.TenantProvisionService;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatHistoryService;
import com.richard.fyoung.customeradmin.workspace.project.dto.ProjectSaveRequest;
import com.richard.fyoung.customeradmin.workspace.project.mapper.AiProjectMapper;
import com.richard.fyoung.customeradmin.workspace.project.mapper.AiProjectSessionMapper;
import com.richard.fyoung.customeradmin.workspace.project.service.ProjectService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** UI 行操作与表单之外的真实写入证据：独立双库、权威迁移、生产租户与数据范围插件。 */
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

    @Test
    void tenantEditPreservesStableCodeStatusAndAccessEpoch() throws Exception {
        long id = 981001L;
        adminJdbc.update("INSERT INTO sys_tenant(id,tenant_code,tenant_name,status,access_epoch) "
            + "VALUES (?,'edit-acceptance','原租户名称','ACTIVE',7)", id);
        var provision = mock(TenantProvisionService.class);
        var publish = mock(TenantAccessPublishTaskService.class);
        var revoke = mock(SessionRevocationService.class);
        var disable = mock(TenantChannelDisableService.class);
        var service = new TenantService(adminTemplate().getMapper(SysTenantMapper.class), provision, publish, revoke, disable);
        var request = new TenantSaveRequest();
        request.setId(id);
        request.setTenantCode("edit-acceptance");
        request.setTenantName("已核对的租户");
        request.setContactName("管理员");
        request.setContactEmail("acceptance@example.test");
        request.setRemark("保留原租户归属");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(adminSource));

        transaction.executeWithoutResult(status -> service.update(request));

        var row = adminJdbc.queryForMap("SELECT tenant_code,tenant_name,status,access_epoch,contact_email FROM sys_tenant WHERE id=?", id);
        assertEquals("edit-acceptance", row.get("tenant_code"));
        assertEquals("已核对的租户", row.get("tenant_name"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(7L, ((Number) row.get("access_epoch")).longValue());
        assertEquals("acceptance@example.test", row.get("contact_email"));
        request.setTenantCode("invalid-reassignment");
        assertThrows(BizException.class, () -> transaction.executeWithoutResult(status -> service.update(request)));
        assertEquals("edit-acceptance", adminJdbc.queryForObject("SELECT tenant_code FROM sys_tenant WHERE id=?", String.class, id));
        verifyNoInteractions(provision, publish, revoke, disable);
    }

    @Test
    void scheduledTaskEditRetainsTenantAndEmitsExactlyOneRefreshEvent() throws Exception {
        long id = 982001L;
        long agent = 982101L;
        seedAgent(agent, "scheduled-acceptance-agent");
        adminJdbc.update("INSERT INTO ai_scheduled_task(id,tenant_id,task_code,task_name,agent_id,prompt,cron,enabled) "
            + "VALUES (?,'row-acceptance','scheduled-edit','原任务',?,'原指令','0 0 9 * * ?',1)", id, agent);
        adminJdbc.update("INSERT INTO ai_scheduled_task(id,tenant_id,task_code,task_name,agent_id,prompt,enabled) "
            + "VALUES (?,'other-tenant','other-scheduled-edit','其他租户任务',?,'其他指令',1)", id + 1, agent);
        var template = adminTemplate();
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var runtime = mock(AdminAgentInstanceFactory.class);
        var service = new ScheduledTaskService(template.getMapper(AiScheduledTaskMapper.class),
            template.getMapper(AiScheduledTaskRunMapper.class), template.getMapper(AiAgentMapper.class),
            runtime, new AdminScheduledTaskProperties(), new AdminSchedulerProperties(), events);
        var request = new ScheduledTaskSaveRequest("scheduled-edit", "已核对任务", agent, "仅查询服务状态", "0 30 9 * * ?", true, "编辑验收");

        service.update(id, request);

        var actual = service.get(id);
        assertEquals("已核对任务", actual.getTaskName());
        assertEquals("仅查询服务状态", actual.getPrompt());
        assertEquals("0 30 9 * * ?", actual.getCron());
        assertEquals("row-acceptance", adminJdbc.queryForObject("SELECT tenant_id FROM ai_scheduled_task WHERE id=?", String.class, id));
        verify(events).publishEvent(new ScheduledTaskChangedEvent(id, false));
        verifyNoInteractions(runtime);
        assertThrows(BizException.class, () -> service.update(id + 1, request));
        assertEquals("其他租户任务", adminJdbc.queryForObject("SELECT task_name FROM ai_scheduled_task WHERE id=?", String.class, id + 1));
    }

    @Test
    void channelEditWithBlankSecretPreservesCipherAndRejectsOtherTenant() throws Exception {
        long id = 983001L;
        seedAgent(983101L, "channel-acceptance-agent");
        var crypto = new AesGcmCryptoUtil("0123456789abcdef0123456789abcdef");
        String cipher = crypto.encrypt("acceptance-only-value");
        adminJdbc.update("INSERT INTO ai_channel_robot(id,tenant_id,channel_type,robot_name,app_key,app_secret_cipher,robot_code,agent_code,status) "
            + "VALUES (?,'row-acceptance','dingtalk','原渠道','acceptance-app',?,'callback-token','channel-acceptance-agent',1)", id, cipher);
        adminJdbc.update("INSERT INTO ai_channel_robot(id,tenant_id,channel_type,robot_name,app_key,app_secret_cipher,robot_code,agent_code,status) "
            + "VALUES (?,'other-tenant','dingtalk','其他租户渠道','other-app',?,'other-token','channel-acceptance-agent',1)", id + 1, cipher);
        var template = adminTemplate();
        var service = new ChannelRobotService(template.getMapper(AiChannelRobotMapper.class), template.getMapper(AiAgentMapper.class), crypto);
        var request = new ChannelRobotSaveRequest("dingtalk", "已核对渠道", "acceptance-app", "", "callback-token",
            "plaintext", null, "channel-acceptance-agent", "per_message", 1, "保留原凭据");

        service.update(id, request);

        var actual = adminJdbc.queryForMap("SELECT robot_name,app_secret_cipher,session_mode,robot_code,tenant_id FROM ai_channel_robot WHERE id=?", id);
        assertEquals("已核对渠道", actual.get("robot_name"));
        assertEquals(cipher, actual.get("app_secret_cipher"));
        assertEquals("per_message", actual.get("session_mode"));
        assertEquals("callback-token", actual.get("robot_code"));
        assertEquals("row-acceptance", actual.get("tenant_id"));
        assertThrows(BizException.class, () -> service.update(id + 1, request));
        assertEquals("其他租户渠道", adminJdbc.queryForObject("SELECT robot_name FROM ai_channel_robot WHERE id=?", String.class, id + 1));
    }

    @Test
    void projectEditAndDeleteRespectSelfScopeAndPreserveConversationStorage() throws Exception {
        long id = 984001L;
        DataScopeContext.set(DataScope.SELF, 7L);
        adminJdbc.update("INSERT INTO ai_project(id,tenant_id,project_name,description,create_by) "
            + "VALUES (?,'row-acceptance','原项目','原描述',7),(?,'row-acceptance','其他人的项目','私有描述',8)", id, id + 1);
        adminJdbc.update("INSERT INTO ai_project_session(project_id,agent_code,session_id,tenant_id,create_by) "
            + "VALUES (?,'acceptance-agent','preserved-session','row-acceptance',7)", id);
        var template = adminTemplate();
        var history = mock(ChatHistoryService.class);
        var service = new ProjectService(template.getMapper(AiProjectMapper.class), template.getMapper(AiProjectSessionMapper.class),
            template.getMapper(AiAgentMapper.class), history, mock(WorkspaceSessionGuard.class));
        var request = new ProjectSaveRequest("已核对项目", "归档说明");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(adminSource));

        service.update(id, request);

        assertEquals("已核对项目", adminJdbc.queryForObject("SELECT project_name FROM ai_project WHERE id=?", String.class, id));
        assertThrows(BizException.class, () -> service.update(id + 1, request));
        assertEquals("其他人的项目", adminJdbc.queryForObject("SELECT project_name FROM ai_project WHERE id=?", String.class, id + 1));
        transaction.executeWithoutResult(status -> service.delete(id));
        assertEquals(1, adminJdbc.queryForObject("SELECT deleted FROM ai_project WHERE id=?", Integer.class, id));
        assertEquals(0, adminJdbc.queryForObject("SELECT COUNT(*) FROM ai_project_session WHERE project_id=?", Integer.class, id));
        verifyNoInteractions(history);
    }

    private static void seedAgent(long id, String code) {
        adminJdbc.update("INSERT INTO ai_agent(id,tenant_id,agent_name,agent_code,model_id,status) "
            + "VALUES (?,'row-acceptance','验收智能体',?,1,1)", id, code);
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
        configuration.addMapper(SysTenantMapper.class);
        configuration.addMapper(AiScheduledTaskMapper.class);
        configuration.addMapper(AiScheduledTaskRunMapper.class);
        configuration.addMapper(AiChannelRobotMapper.class);
        configuration.addMapper(AiProjectMapper.class);
        configuration.addMapper(AiProjectSessionMapper.class);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenantProperties, new DataScopeProperties()));
        var sqlFactory = factory.getObject();
        // 与生产一致填充更新审计字段，特别是逻辑删除生成的部分实体。
        GlobalConfigUtils.getGlobalConfig(sqlFactory.getConfiguration())
            .setMetaObjectHandler(new MyMetaObjectHandler());
        return new SqlSessionTemplate(sqlFactory);
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
