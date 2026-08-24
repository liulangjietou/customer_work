package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity.AiScheduledTask;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity.AiScheduledTaskRun;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskRunMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminScheduledTaskProperties;
import com.richard.fyoung.customeradmin.config.AdminSchedulerProperties;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduledTaskService} 单测：重点覆盖执行入口 {@link ScheduledTaskService#execute}
 * （成功落历史 / 异常落 FAILED 历史 / disabled 任务 fast-fail）与 taskCode 唯一性校验。
 * @author owlzhangfq@gmail.com
 */
class ScheduledTaskServiceTest {

    private AiScheduledTaskMapper taskMapper;
    private AiScheduledTaskRunMapper runMapper;
    private AiAgentMapper agentMapper;
    private AdminAgentInstanceFactory agentInstanceFactory;
    private ScheduledTaskService service;
    private ReActAgent runtimeAgent;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.DEFAULT);
        taskMapper = mock(AiScheduledTaskMapper.class);
        runMapper = mock(AiScheduledTaskRunMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        agentInstanceFactory = mock(AdminAgentInstanceFactory.class);
        AdminScheduledTaskProperties properties = new AdminScheduledTaskProperties();
        AdminSchedulerProperties schedulerProperties = new AdminSchedulerProperties();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ScheduledTaskService(taskMapper, runMapper, agentMapper, agentInstanceFactory,
            properties, schedulerProperties, eventPublisher);

        runtimeAgent = mock(ReActAgent.class);
        when(agentInstanceFactory.contextFor(anyString(), anyString())).thenReturn(mock(RuntimeContext.class));
    }

    @AfterEach
    void tearDown() {
        AgentInvocationIdentityContext.clear();
        TenantContext.clear();
    }

    private AiScheduledTask enabledTask() {
        AiScheduledTask task = new AiScheduledTask();
        task.setId(1L);
        task.setTaskCode("daily-report");
        task.setTaskName("每日报表");
        task.setAgentId(10L);
        task.setPrompt("生成今日报表");
        task.setEnabled(1);
        return task;
    }

    private AiAgent enabledAgent() {
        AiAgent agent = new AiAgent();
        agent.setId(10L);
        agent.setAgentCode("report-agent");
        agent.setStatus(1);
        return agent;
    }

    @Test
    void execute_shouldPersistSuccessRun_whenAgentReplies() {
        when(taskMapper.selectOne(any())).thenReturn(enabledTask());
        when(agentMapper.selectById(10L)).thenReturn(enabledAgent());
        when(agentInstanceFactory.build("report-agent")).thenReturn(runtimeAgent);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT)
            .content(TextBlock.builder().text("今日报表已生成").build()).build();
        when(runtimeAgent.call(any(List.class), any(RuntimeContext.class))).thenReturn(Mono.just(reply));

        AiScheduledTaskRun run = service.execute("daily-report", ScheduledTaskService.TRIGGER_TYPE_MANUAL);

        assertEquals(ScheduledTaskService.STATUS_SUCCESS, run.getStatus());
        assertEquals("今日报表已生成", run.getOutput());
        assertNotNull(run.getCostMs());
        verify(runMapper).insert(run);
    }

    @Test
    void execute_shouldEstablishInternalIdentity_whenSchedulerHasNoWebSubject() {
        when(taskMapper.selectOne(any())).thenReturn(enabledTask());
        when(agentMapper.selectById(10L)).thenReturn(enabledAgent());
        when(agentInstanceFactory.build("report-agent")).thenAnswer(invocation -> {
            AgentInvocationIdentity identity = AgentInvocationIdentity.capture();
            assertEquals(TenantContext.DEFAULT, identity.tenantId());
            assertEquals(QuotaSubjectType.API_KEY, identity.subjectType());
            assertEquals("scheduled-task:1", identity.subjectId());
            assertEquals(AgentInvocationIdentity.CHANNEL_INTERNAL, identity.channelCode());
            assertEquals("report-agent", identity.agentCode());
            return runtimeAgent;
        });
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT)
            .content(TextBlock.builder().text("done").build()).build();
        when(runtimeAgent.call(any(List.class), any(RuntimeContext.class))).thenReturn(Mono.just(reply));

        TenantContext.callWith(TenantContext.DEFAULT,
            () -> service.execute("daily-report", ScheduledTaskService.TRIGGER_TYPE_INTERNAL));

        assertNull(AgentInvocationIdentityContext.get());
    }

    @Test
    void execute_shouldPersistFailedRun_whenAgentThrows() {
        when(taskMapper.selectOne(any())).thenReturn(enabledTask());
        when(agentMapper.selectById(10L)).thenReturn(enabledAgent());
        when(agentInstanceFactory.build("report-agent")).thenReturn(runtimeAgent);
        when(runtimeAgent.call(any(List.class), any(RuntimeContext.class)))
            .thenReturn(Mono.error(new RuntimeException("model timeout")));

        AiScheduledTaskRun run = service.execute("daily-report", ScheduledTaskService.TRIGGER_TYPE_XXL_JOB);

        assertEquals(ScheduledTaskService.STATUS_FAILED, run.getStatus());
        assertEquals("model timeout", run.getErrorMessage());
        verify(runMapper).insert(run);
    }

    @Test
    void execute_shouldFastFail_whenTaskDisabled() {
        AiScheduledTask disabled = enabledTask();
        disabled.setEnabled(0);
        when(taskMapper.selectOne(any())).thenReturn(disabled);

        BizException ex = assertThrows(BizException.class,
            () -> service.execute("daily-report", ScheduledTaskService.TRIGGER_TYPE_MANUAL));

        assertEquals(ResultCode.SCHEDULED_TASK_DISABLED, ex.getResultCode());
    }

    @Test
    void execute_shouldFastFail_whenTaskNotFound() {
        when(taskMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
            () -> service.execute("unknown-task", ScheduledTaskService.TRIGGER_TYPE_MANUAL));

        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
    }

    @Test
    void create_shouldRejectDuplicateTaskCode() {
        when(taskMapper.exists(any())).thenReturn(true);
        ScheduledTaskSaveRequest request = new ScheduledTaskSaveRequest(
            "daily-report", "每日报表", 10L, "生成今日报表", null, true, null);

        BizException ex = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, ex.getResultCode());
    }

    @Test
    void create_shouldRejectDisabledAgent() {
        when(taskMapper.exists(any())).thenReturn(false);
        AiAgent disabledAgent = enabledAgent();
        disabledAgent.setStatus(0);
        when(agentMapper.selectById(10L)).thenReturn(disabledAgent);
        ScheduledTaskSaveRequest request = new ScheduledTaskSaveRequest(
            "daily-report", "每日报表", 10L, "生成今日报表", null, true, null);

        BizException ex = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ResultCode.AGENT_DISABLED, ex.getResultCode());
    }

    @Test
    void create_shouldInsertTask_whenValid() {
        when(taskMapper.exists(any())).thenReturn(false);
        when(agentMapper.selectById(10L)).thenReturn(enabledAgent());
        ScheduledTaskSaveRequest request = new ScheduledTaskSaveRequest(
            "daily-report", "每日报表", 10L, "生成今日报表", "0 0 * * * ?", true, "备注");

        service.create(request);

        verify(taskMapper).insert(any(AiScheduledTask.class));
    }

    @Test
    void trigger_shouldRejectUnknownId() {
        when(taskMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.trigger(999L));
    }

    @Test
    void create_shouldRejectInvalidCron() {
        when(taskMapper.exists(any())).thenReturn(false);
        when(agentMapper.selectById(10L)).thenReturn(enabledAgent());
        ScheduledTaskSaveRequest request = new ScheduledTaskSaveRequest(
            "daily-report", "每日报表", 10L, "生成今日报表", "not-a-cron", true, null);

        BizException ex = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ResultCode.SCHEDULER_CRON_INVALID, ex.getResultCode());
    }

    @Test
    void create_shouldAcceptValidCron() {
        when(taskMapper.exists(any())).thenReturn(false);
        when(agentMapper.selectById(10L)).thenReturn(enabledAgent());
        ScheduledTaskSaveRequest request = new ScheduledTaskSaveRequest(
            "daily-report", "每日报表", 10L, "生成今日报表", "0 0/5 * * * ?", true, null);

        service.create(request);

        verify(taskMapper).insert(any(AiScheduledTask.class));
    }

    @Test
    void create_shouldAcceptBlankCron_asNonPeriodic() {
        when(taskMapper.exists(any())).thenReturn(false);
        when(agentMapper.selectById(10L)).thenReturn(enabledAgent());
        ScheduledTaskSaveRequest request = new ScheduledTaskSaveRequest(
            "daily-report", "每日报表", 10L, "生成今日报表", "  ", true, null);

        service.create(request);

        verify(taskMapper).insert(any(AiScheduledTask.class));
    }
}
