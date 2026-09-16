package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskPageQuery;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskRunMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service.ScheduledTaskService;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.mp.MyMetaObjectHandler;
import com.richard.fyoung.customeradmin.config.AdminScheduledTaskProperties;
import com.richard.fyoung.customeradmin.config.AdminSchedulerProperties;
import com.richard.fyoung.customeradmin.config.MybatisPlusConfig;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.datascope.DataScopeContext;
import com.richard.fyoung.customeradmin.datascope.DataScopeProperties;
import com.richard.fyoung.customeradmin.governance.change.GovernanceProperties;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernanceAuditEventMapper;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernedChangeRequestMapper;
import com.richard.fyoung.customeradmin.governance.change.service.GovernanceAuditWriter;
import com.richard.fyoung.customeradmin.governance.change.service.GovernedChangeStateService;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.access.TenantChannelDisableService;
import com.richard.fyoung.customeradmin.tenant.access.service.TenantAccessPublishTaskService;
import com.richard.fyoung.customeradmin.tenant.entity.TenantStatus;
import com.richard.fyoung.customeradmin.tenant.mapper.SysTenantMapper;
import com.richard.fyoung.customeradmin.tenant.service.TenantProvisionService;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatHistoryService;
import com.richard.fyoung.customeradmin.workspace.project.mapper.AiProjectMapper;
import com.richard.fyoung.customeradmin.workspace.project.mapper.AiProjectSessionMapper;
import com.richard.fyoung.customeradmin.workspace.project.service.ProjectService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import com.richard.fyoung.customeradmin.workspace.task.mapper.AiAgentTaskMapper;
import com.richard.fyoung.customeradmin.workspace.task.runtime.AgentTaskExecutorProperties;
import com.richard.fyoung.customeradmin.workspace.task.runtime.MybatisTaskRepository;
import com.richard.fyoung.customeradmin.workspace.task.service.AgentTaskService;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/** 专用操作的真实落库证据；外部模型、策略投递和登录撤销使用明确替身。 */
class AdminLifecycleActionsAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final String PORT = System.getenv().getOrDefault("MYSQL_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "lifecycle-acceptance";
    private static final long OPERATOR = 7L;
    private static String database;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate sql;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void createOwnDatabase() throws Exception {
        String candidate = "admin_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate);
            database = candidate;
        }
        var source = new DriverManagerDataSource(url(database), USER, PASSWORD);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .placeholderReplacement(false).load().migrate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(SysTenantMapper.class, AiChannelBindingMapper.class,
            AiChannelRobotMapper.class, AiAgentMapper.class, AiScheduledTaskMapper.class,
            AiScheduledTaskRunMapper.class, AiProjectMapper.class, AiProjectSessionMapper.class,
            GovernedChangeRequestMapper.class, GovernanceAuditEventMapper.class, AiAgentTaskMapper.class)) {
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
        sql = new SqlSessionTemplate(sessionFactory);
    }

    @BeforeEach
    void bindOperator() {
        TenantContext.set(TENANT);
        DataScopeContext.set(DataScope.TENANT, OPERATOR);
    }

    @AfterEach
    void clearOperator() {
        AgentInvocationIdentityContext.clear();
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
    void tenantStatusRollsBackOnEnqueueFailureAndOffboardingPreservesOtherTenantChannels() {
        long id = 991001L;
        jdbc.update("INSERT INTO sys_tenant(id,tenant_code,tenant_name,status,access_epoch) VALUES (?,?,?,'ACTIVE',7)",
            id, TENANT, "状态验收租户");
        for (String tenant : List.of(TENANT, "foreign-lifecycle")) {
            jdbc.update("INSERT INTO ai_channel_robot(tenant_id,channel_type,robot_name,app_key,app_secret_cipher,robot_code,agent_code,status) "
                + "VALUES (?,'dingtalk',?,?,'fixture-only-cipher',?,'lifecycle-agent',1)", tenant, tenant, tenant, tenant);
        }
        var publish = mock(TenantAccessPublishTaskService.class);
        var revoke = mock(SessionRevocationService.class);
        var channels = new TenantChannelDisableService(sql.getMapper(AiChannelBindingMapper.class), sql.getMapper(AiChannelRobotMapper.class));
        var service = new TenantService(sql.getMapper(SysTenantMapper.class), mock(TenantProvisionService.class), publish, revoke, channels);
        doThrow(new IllegalStateException("enqueue unavailable")).when(publish).enqueue(any(), any());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> service.changeStatus(id, TenantStatus.SUSPENDED)));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM sys_tenant WHERE id=?", String.class, id));
        assertEquals(7L, jdbc.queryForObject("SELECT access_epoch FROM sys_tenant WHERE id=?", Long.class, id));
        verifyNoInteractions(revoke);
        reset(publish);

        long epoch = 7;
        for (TenantStatus target : List.of(TenantStatus.SUSPENDED, TenantStatus.ACTIVE, TenantStatus.TERMINATED)) {
            transaction.executeWithoutResult(tx -> service.changeStatus(id, target));
            assertEquals(target.name(), jdbc.queryForObject("SELECT status FROM sys_tenant WHERE id=?", String.class, id));
            assertEquals(++epoch, jdbc.queryForObject("SELECT access_epoch FROM sys_tenant WHERE id=?", Long.class, id));
        }
        assertThrows(BizException.class, () -> transaction.executeWithoutResult(tx -> service.changeStatus(id, TenantStatus.ACTIVE)));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM ai_channel_robot WHERE tenant_id=?", Integer.class, TENANT));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM ai_channel_robot WHERE tenant_id='foreign-lifecycle'", Integer.class));
        verify(publish, times(3)).enqueue(any(), any());
        verify(revoke, times(3)).revokeTenantAfterCommit(TENANT);
    }

    @Test
    void projectRemovalDeletesOnlyTheOriginalSelfScopedLink() {
        DataScopeContext.set(DataScope.SELF, OPERATOR);
        jdbc.update("INSERT INTO ai_project(id,tenant_id,project_name,create_by) VALUES (992001,?,'保留项目',7)", TENANT);
        jdbc.update("INSERT INTO ai_project_session(project_id,tenant_id,agent_code,session_id,create_by) "
            + "VALUES (992001,?,'lifecycle-agent','own-session',7),(992001,?,'lifecycle-agent','other-user-session',8),"
            + "(992002,'foreign-lifecycle','lifecycle-agent','foreign-session',7)", TENANT, TENANT);
        var guard = mock(WorkspaceSessionGuard.class);
        var history = mock(ChatHistoryService.class);
        var service = new ProjectService(sql.getMapper(AiProjectMapper.class), sql.getMapper(AiProjectSessionMapper.class),
            sql.getMapper(AiAgentMapper.class), history, guard);

        service.removeSession(992001L, "lifecycle-agent", "own-session", OPERATOR);
        service.removeSession(992001L, "lifecycle-agent", "other-user-session", OPERATOR);
        service.removeSession(992002L, "lifecycle-agent", "foreign-session", OPERATOR);

        assertEquals(List.of("foreign-session", "other-user-session"), jdbc.queryForList(
            "SELECT session_id FROM ai_project_session ORDER BY session_id", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT deleted FROM ai_project WHERE id=992001", Integer.class));
        verify(guard).requireOwned("lifecycle-agent", "own-session", OPERATOR);
        verifyNoInteractions(history);
    }

    @Test
    void scheduledToggleAndTriggerPersistOnlyOwnedRunsAndCloseEachInstance() {
        long id = 993001L;
        jdbc.update("INSERT INTO ai_agent(id,tenant_id,agent_name,agent_code,model_id,status) VALUES (993101,?,'验收助手','lifecycle-agent',1,1)", TENANT);
        jdbc.update("INSERT INTO ai_scheduled_task(id,tenant_id,task_code,task_name,agent_id,prompt,enabled) "
            + "VALUES (?,?,'lifecycle-task','验收任务',993101,'验收指令',1),(993002,'LIFECYCLE-ACCEPTANCE','foreign-task','外租户任务',993101,'外租户指令',1)", id, TENANT);
        var factory = mock(AdminAgentInstanceFactory.class);
        var success = mock(HarnessAgent.class);
        var failure = mock(HarnessAgent.class);
        when(factory.build("lifecycle-agent")).thenReturn(success, failure);
        when(factory.contextFor(anyString(), anyString())).thenReturn(mock(RuntimeContext.class));
        when(success.call(any(List.class), any(RuntimeContext.class))).thenReturn(Mono.just(Msg.builder()
            .role(MsgRole.ASSISTANT).content(TextBlock.builder().text("已核对输出").build()).build()));
        when(failure.call(any(List.class), any(RuntimeContext.class))).thenReturn(Mono.error(new IllegalStateException("model unavailable")));
        var service = new ScheduledTaskService(sql.getMapper(AiScheduledTaskMapper.class), sql.getMapper(AiScheduledTaskRunMapper.class),
            sql.getMapper(AiAgentMapper.class), factory, new AdminScheduledTaskProperties(), new AdminSchedulerProperties(), mock(ApplicationEventPublisher.class));

        service.disable(id);
        assertEquals(0, jdbc.queryForObject("SELECT enabled FROM ai_scheduled_task WHERE id=?", Integer.class, id));
        assertThrows(BizException.class, () -> service.trigger(id));
        service.enable(id);
        assertEquals("SUCCESS", service.trigger(id).getStatus());
        assertEquals("FAILED", service.trigger(id).getStatus());
        assertThrows(BizException.class, () -> service.trigger(993002L));
        assertEquals(2L, service.runs(id, new ScheduledTaskPageQuery()).getTotal());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_scheduled_task_run WHERE task_id=? AND tenant_id=?", Integer.class, id, TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_scheduled_task_run WHERE task_id=993002", Integer.class));
        verify(success).close();
        verify(failure).close();
    }

    @Test
    void agentTaskCancellationPersistsAndInterruptsOnlyTheOwnedTenantTask() {
        var mapper = sql.getMapper(AiAgentTaskMapper.class);
        var repository = new MybatisTaskRepository(mapper, new AgentTaskExecutorProperties());
        var running = new CompletableFuture<String>();
        try {
            repository.putTask(RuntimeContext.empty(), "cancel-owned", "child-agent", "cancel-session",
                new TaskRunSpec.AdoptedTaskRunSpec(running));
            jdbc.update("INSERT INTO ai_agent_task(task_id,parent_agent_code,sub_agent_id,parent_session_id,tenant_id,status,result,cancel_requested,created_at,updated_at) "
                + "VALUES ('cancel-foreign','parent','child','foreign-session','LIFECYCLE-ACCEPTANCE','RUNNING',NULL,0,NOW(),NOW()),"
                + "('cancel-completed','parent','child','completed-session',?,'COMPLETED','保留原结果',0,NOW(),NOW())", TENANT);
            var service = new AgentTaskService(mapper, repository);

            assertThrows(BizException.class, () -> service.cancel("cancel-foreign"));
            service.cancel("cancel-owned");
            service.cancel("cancel-owned");
            assertTrue(running.isCancelled());
            assertEquals("CANCELLED", service.get("cancel-owned").getStatus());
            assertTrue(service.get("cancel-owned").getCancelRequested());
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ai_agent_task WHERE task_id='cancel-foreign'", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT cancel_requested FROM ai_agent_task WHERE task_id='cancel-foreign'", Integer.class));

            service.cancel("cancel-completed");
            assertEquals("COMPLETED", service.get("cancel-completed").getStatus());
            assertEquals("保留原结果", service.get("cancel-completed").getResult());
        } finally {
            repository.shutdown();
        }
    }

    @Test
    void governanceDecisionAndAuditAreStoredTogetherAndCannotBeRepeatedOrCrossTenant() {
        var service = governanceService();
        transaction.executeWithoutResult(tx -> service.create(change("approved", TENANT)));
        assertThrows(BizException.class, () -> transaction.executeWithoutResult(tx -> service.claim("approved", TENANT, OPERATOR, "maker", "自审")));
        transaction.executeWithoutResult(tx -> service.claim("approved", TENANT, 8L, "checker", " 已核对 "));
        assertThrows(BizException.class, () -> transaction.executeWithoutResult(tx -> service.claim("approved", TENANT, 9L, "other", "重复审批")));
        transaction.executeWithoutResult(tx -> service.complete("approved", TENANT, "{\"accepted\":true}"));
        assertEquals("EXECUTED", jdbc.queryForObject("SELECT status FROM ai_governed_change_request WHERE id='approved'", String.class));
        assertEquals(List.of("SUBMITTED", "APPROVED", "EXECUTED"), jdbc.queryForList(
            "SELECT event_type FROM ai_governance_audit_event WHERE request_id='approved' ORDER BY sequence_no", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_governance_audit_event current_event JOIN ai_governance_audit_event previous_event "
            + "ON current_event.request_id=previous_event.request_id AND current_event.sequence_no=previous_event.sequence_no+1 "
            + "WHERE current_event.request_id='approved' AND current_event.previous_hash<>previous_event.event_hash", Integer.class));

        transaction.executeWithoutResult(tx -> service.create(change("rejected", TENANT)));
        transaction.executeWithoutResult(tx -> service.reject("rejected", TENANT, 8L, "checker", "不予通过"));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM ai_governed_change_request WHERE id='rejected'", String.class));
        TenantContext.runWith("LIFECYCLE-ACCEPTANCE", () -> transaction.executeWithoutResult(tx -> service.create(change("foreign", "LIFECYCLE-ACCEPTANCE"))));
        assertThrows(BizException.class, () -> transaction.executeWithoutResult(tx -> service.claim("foreign", TENANT, 8L, "checker", "越界")));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM ai_governed_change_request WHERE id='foreign'", String.class));
    }

    @Test
    void governanceAuditFailureRollsBackTheClaim() {
        transaction.executeWithoutResult(tx -> governanceService().create(change("audit-failure", TENANT)));
        var writer = mock(GovernanceAuditWriter.class);
        doThrow(new IllegalStateException("audit unavailable")).when(writer).append(any(), anyString(), any(), anyString(), anyString(), any());
        var service = new GovernedChangeStateService(sql.getMapper(GovernedChangeRequestMapper.class), writer);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> service.claim("audit-failure", TENANT, 8L, "checker", "依据")));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM ai_governed_change_request WHERE id='audit-failure'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_governance_audit_event WHERE request_id='audit-failure'", Integer.class));
    }

    private static GovernedChangeStateService governanceService() {
        return new GovernedChangeStateService(sql.getMapper(GovernedChangeRequestMapper.class),
            new GovernanceAuditWriter(sql.getMapper(GovernanceAuditEventMapper.class), new GovernanceProperties()));
    }

    private static AiGovernedChangeRequest change(String id, String tenant) {
        var request = new AiGovernedChangeRequest();
        request.setId(id);
        request.setTenantId(tenant);
        request.setChangeType("CONFIG_ROLLBACK");
        request.setTargetKey("lifecycle-agent");
        request.setPayloadJson("{}");
        request.setPayloadHash("a".repeat(64));
        request.setMakerId(OPERATOR);
        request.setMakerName("maker");
        request.setStatus("PENDING");
        request.setCreateTime(LocalDateTime.now());
        request.setUpdateTime(LocalDateTime.now());
        request.setExpiresAt(LocalDateTime.now().plusDays(1));
        return request;
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
