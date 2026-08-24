package com.richard.fyoung.customeradmin.workspace.task.runtime;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.workspace.task.entity.AiAgentTask;
import com.richard.fyoung.customeradmin.workspace.task.mapper.AiAgentTaskMapper;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台任务落库仓储单测：状态流转、取消的两个时间窗、进程重启后按库记录重建、远程 spec 拒绝。
 * @author owlzhangfq@gmail.com
 */
class MybatisTaskRepositoryTest {

    private static final String TASK_ID = "task-1";
    private static final String SESSION_ID = "session-1";
    private static final String SUB_AGENT = "code-reviewer";
    private static final long WAIT_MS = 3000;

    private AiAgentTaskMapper mapper;
    private MybatisTaskRepository repository;

    /**
     * MyBatis-Plus 的 Lambda 条件构造依赖实体的 TableInfo 缓存，容器外单测里没人初始化它，
     * 不预热会在第一次 {@code .set(AiAgentTask::getXxx, ...)} 时抛 "can not find lambda cache"。
     * 写法与 {@code AiCodingAuditServiceTest} 一致。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentTask.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiAgentTaskMapper.class);
        when(mapper.insert(any(AiAgentTask.class))).thenReturn(1);
        when(mapper.update(any(), any())).thenReturn(1);
        when(mapper.updateOwnedStatus(anyString(), anyString(), anyString(), any(), any(), any(),
            any(), any(), any())).thenReturn(1);
        when(mapper.markOwnedCancelled(anyString(), anyString(), any())).thenReturn(1);
        stubExistingRecord(TaskStatus.PENDING, false);

        AgentTaskExecutorProperties properties = new AgentTaskExecutorProperties();
        properties.setOwnerId("pod-a");
        repository = new MybatisTaskRepository(mapper, properties);
        // 不走 @PostConstruct（无容器），显式给一个单线程池，配合 awaitIdle 让异步执行可断言
        repository.useExecutor(Executors.newSingleThreadExecutor());
    }

    /** 让 selectByTaskId 返回一条指定状态的记录。 */
    private void stubExistingRecord(TaskStatus status, boolean cancelRequested) {
        AiAgentTask record = new AiAgentTask();
        record.setId(1L);
        record.setTaskId(TASK_ID);
        record.setSubAgentId(SUB_AGENT);
        record.setParentSessionId(SESSION_ID);
        record.setStatus(status.name());
        record.setCancelRequested(cancelRequested);
        record.setCreatedAt(LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(record);
    }

    /** 收集所有 update 调用里被 set 的值（LambdaUpdateWrapper 把值放在 paramNameValuePairs 里）。 */
    @SuppressWarnings("unchecked")
    private List<Object> capturedSetValues() {
        ArgumentCaptor<LambdaUpdateWrapper<AiAgentTask>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, atLeastOnce()).update(any(), captor.capture());
        List<Object> values = new ArrayList<>();
        captor.getAllValues().forEach(w -> values.addAll(w.getParamNameValuePairs().values()));
        return values;
    }

    private TaskRunSpec.LocalTaskRunSpec localSpec(java.util.function.Supplier<String> supplier) {
        return new TaskRunSpec.LocalTaskRunSpec(supplier);
    }

    @Test
    void putTask_shouldInsertPendingRecordAndRunToCompletion() throws Exception {
        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            localSpec(() -> "审查完成，无高危问题"));

        assertTrue(task.waitForCompletion(WAIT_MS), "任务应在超时前跑完");
        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
        assertEquals("审查完成，无高危问题", task.getResult());

        ArgumentCaptor<AiAgentTask> inserted = ArgumentCaptor.forClass(AiAgentTask.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(TaskStatus.PENDING.name(), inserted.getValue().getStatus(), "创建即落 PENDING");
        assertEquals(SESSION_ID, inserted.getValue().getParentSessionId());

        verify(mapper).updateOwnedStatus(eq(TASK_ID), anyString(), eq(TaskStatus.RUNNING.name()),
            any(), any(), any(), isNull(), isNull(), isNull());
        verify(mapper).updateOwnedStatus(eq(TASK_ID), anyString(), eq(TaskStatus.COMPLETED.name()),
            any(), isNull(), isNull(), any(), eq("审查完成，无高危问题"), isNull());
    }

    @Test
    void failedTask_shouldPersistFailedStatusAndKeepError() throws Exception {
        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            localSpec(() -> {
                throw new IllegalStateException("模型调用超时");
            }));

        assertTrue(task.waitForCompletion(WAIT_MS));
        assertEquals(TaskStatus.FAILED, task.getTaskStatus());
        assertNotNull(task.getError(), "失败原因应能被父智能体取到");

        verify(mapper).updateOwnedStatus(eq(TASK_ID), anyString(), eq(TaskStatus.FAILED.name()),
            any(), isNull(), isNull(), any(), isNull(), eq("模型调用超时"));
    }

    @Test
    void putTask_shouldPropagateTenantContextAndPersistRawAgentCode() throws Exception {
        AtomicReference<String> workerTenant = new AtomicReference<>();
        RuntimeContext context = RuntimeContext.builder()
            .userId("tenant-a::review-agent")
            .sessionId(SESSION_ID)
            .build();
        BackgroundTask task;
        TenantContext.set("tenant-a");
        try {
            task = repository.putTask(context, TASK_ID, SUB_AGENT, SESSION_ID,
                localSpec(() -> {
                    workerTenant.set(TenantContext.get());
                    return "done";
                }));
        } finally {
            TenantContext.clear();
        }

        assertTrue(task.waitForCompletion(WAIT_MS));
        assertEquals("tenant-a", workerTenant.get(), "线程池内必须恢复提交请求的租户上下文");
        ArgumentCaptor<AiAgentTask> inserted = ArgumentCaptor.forClass(AiAgentTask.class);
        verify(mapper).insert(inserted.capture());
        assertEquals("review-agent", inserted.getValue().getParentAgentCode(), "业务列不能持久化内部作用域前缀");
    }

    @Test
    void putTask_shouldPersistReplayInputAndTrustedIdentity() throws Exception {
        AgentTaskReplayContext replayContext = new AgentTaskReplayContext();
        replayContext.offer(new AgentTaskReplayContext.ReplaySpec("call-1", SUB_AGENT, "review raw diff"));
        AgentInvocationIdentity identity = new AgentInvocationIdentity("tenant-a", QuotaSubjectType.ADMIN_USER,
            "42", true, 7L, AgentInvocationIdentity.CHANNEL_ADMIN, SESSION_ID, "parent-agent");
        RuntimeContext context = RuntimeContext.builder()
            .userId("tenant-a::parent-agent")
            .sessionId(SESSION_ID)
            .put(AgentTaskReplayContext.class, replayContext)
            .put(AgentInvocationIdentity.class, identity)
            .build();

        BackgroundTask task = repository.putTask(context, TASK_ID, SUB_AGENT, SESSION_ID,
            localSpec(() -> "done"));

        assertTrue(task.waitForCompletion(WAIT_MS));
        ArgumentCaptor<AiAgentTask> inserted = ArgumentCaptor.forClass(AiAgentTask.class);
        verify(mapper).insert(inserted.capture());
        AiAgentTask record = inserted.getValue();
        assertTrue(record.getReplayable());
        assertEquals("review raw diff", record.getTaskInput());
        assertEquals("replay-" + TASK_ID, record.getChildSessionId());
        assertEquals("tenant-a::parent-agent", record.getRuntimeUserId());
        assertEquals(QuotaSubjectType.ADMIN_USER.name(), record.getSubjectType());
        assertEquals("42", record.getSubjectId());
        assertEquals(7L, record.getAccessEpoch());
        assertEquals(AgentInvocationIdentity.CHANNEL_ADMIN, record.getChannelCode());
        assertNull(replayContext.claim(SUB_AGENT), "重放输入只能被对应任务消费一次");
    }

    @Test
    void heartbeatOwnedTasks_shouldRenewOnlyCurrentOwnerLease() {
        when(mapper.heartbeatOwned(anyString(), any(), any())).thenReturn(2);

        repository.heartbeatOwnedTasks();

        ArgumentCaptor<LocalDateTime> heartbeat = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> lease = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).heartbeatOwned(eq("pod-a"), heartbeat.capture(), lease.capture());
        assertTrue(lease.getValue().isAfter(heartbeat.getValue()));
    }

    @Test
    void recoverExpiredTasks_shouldClaimAndReplayWithOriginalSecurityContext() throws Exception {
        AiAgentTask candidate = replayCandidate();
        when(mapper.selectExpiredReplayable(any(), eq(3), eq(20))).thenReturn(List.of(candidate));
        when(mapper.claimExpired(eq(TASK_ID), eq("pod-a"), any(), any(), eq(3))).thenReturn(1);
        when(mapper.failExpiredUnrecoverable(any(), eq(3), anyString())).thenReturn(0);
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<AgentInvocationIdentity> identity = new AtomicReference<>();
        AtomicReference<QuotaSubject> quotaSubject = new AtomicReference<>();
        AtomicReference<RuntimeContext> runtimeContext = new AtomicReference<>();
        repository.setReplayExecutor((task, context) -> {
            tenant.set(TenantContext.get());
            identity.set(AgentInvocationIdentityContext.get());
            quotaSubject.set(QuotaSubjectContext.get());
            runtimeContext.set(context);
            assertEquals("review raw diff", task.getTaskInput());
            return "replayed";
        });

        repository.recoverExpiredTasks();

        assertTrue(repository.awaitIdle(WAIT_MS));
        assertEquals("tenant-a", tenant.get());
        assertNotNull(identity.get());
        assertEquals(QuotaSubjectType.ADMIN_USER, identity.get().subjectType());
        assertEquals("42", identity.get().subjectId());
        assertEquals(7L, identity.get().accessEpoch());
        assertEquals(AgentInvocationIdentity.CHANNEL_ADMIN, identity.get().channelCode());
        assertEquals(new QuotaSubject(QuotaSubjectType.ADMIN_USER, "42"), quotaSubject.get());
        assertEquals("tenant-a::parent-agent", runtimeContext.get().getUserId());
        assertEquals("replay-" + TASK_ID, runtimeContext.get().getSessionId());
        assertEquals(identity.get(), runtimeContext.get().get(AgentInvocationIdentity.class));
        assertNull(TenantContext.get(), "恢复执行完成后不能污染维护线程上下文");
        assertNull(AgentInvocationIdentityContext.get());
        assertNull(QuotaSubjectContext.get());
        assertEquals(2, candidate.getAttemptCount());
        verify(mapper).updateOwnedStatus(eq(TASK_ID), eq("pod-a"), eq(TaskStatus.COMPLETED.name()),
            any(), isNull(), isNull(), any(), eq("replayed"), isNull());
    }

    @Test
    void recoverExpiredTasks_shouldNotReplayWhenCasClaimIsLost() throws Exception {
        AiAgentTask candidate = replayCandidate();
        when(mapper.selectExpiredReplayable(any(), eq(3), eq(20))).thenReturn(List.of(candidate));
        when(mapper.claimExpired(eq(TASK_ID), eq("pod-a"), any(), any(), eq(3))).thenReturn(0);
        when(mapper.failExpiredUnrecoverable(any(), eq(3), anyString())).thenReturn(0);
        AtomicBoolean replayed = new AtomicBoolean();
        repository.setReplayExecutor((task, context) -> {
            replayed.set(true);
            return "unexpected";
        });

        repository.recoverExpiredTasks();

        assertTrue(repository.awaitIdle(WAIT_MS));
        assertFalse(replayed.get(), "CAS 失败表示其它 Pod 已领取，本 Pod 不能重复执行");
        assertEquals(0, repository.activeTaskCount());
        verify(mapper, never()).updateOwnedStatus(anyString(), anyString(), anyString(), any(), any(), any(),
            any(), any(), any());
    }

    private AiAgentTask replayCandidate() {
        AiAgentTask candidate = new AiAgentTask();
        candidate.setTaskId(TASK_ID);
        candidate.setSubAgentId(SUB_AGENT);
        candidate.setParentAgentCode("parent-agent");
        candidate.setParentSessionId(SESSION_ID);
        candidate.setTenantId("tenant-a");
        candidate.setStatus(TaskStatus.RUNNING.name());
        candidate.setOwnerId("dead-pod");
        candidate.setAttemptCount(1);
        candidate.setReplayable(true);
        candidate.setTaskInput("review raw diff");
        candidate.setChildSessionId("replay-" + TASK_ID);
        candidate.setRuntimeUserId("tenant-a::parent-agent");
        candidate.setSubjectType(QuotaSubjectType.ADMIN_USER.name());
        candidate.setSubjectId("42");
        candidate.setSubjectAuthenticated(true);
        candidate.setAccessEpoch(7L);
        candidate.setChannelCode(AgentInvocationIdentity.CHANNEL_ADMIN);
        return candidate;
    }

    @Test
    void cancelBeforeExecution_shouldSkipRunningTheSupplier() throws Exception {
        // 任务还在队列里排队时被取消：future 中断无效，只能靠开跑前查一次取消标志自我了断
        stubExistingRecord(TaskStatus.PENDING, true);
        AtomicBoolean executed = new AtomicBoolean(false);

        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            localSpec(() -> {
                executed.set(true);
                return "不该跑到这里";
            }));

        assertTrue(task.waitForCompletion(WAIT_MS));
        assertTrue(repository.awaitIdle(WAIT_MS));
        assertFalse(executed.get(), "已请求取消的任务不该真的执行");
        assertNull(task.getResult(), "取消的任务没有结果");
        verify(mapper).markOwnedCancelled(eq(TASK_ID), anyString(), any());
    }

    @Test
    void cancelTask_shouldPersistFlagAndInterruptRunningFuture() {
        stubExistingRecord(TaskStatus.RUNNING, false);
        CompletableFuture<String> running = new CompletableFuture<>();
        repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            new TaskRunSpec.AdoptedTaskRunSpec(running));

        assertTrue(repository.cancelTask(RuntimeContext.empty(), SESSION_ID, TASK_ID));

        assertTrue(running.isCancelled(), "本进程内正在跑的任务应被中断");
        List<Object> setValues = capturedSetValues();
        assertTrue(setValues.contains(Boolean.TRUE), "取消标志应落库（供排队中/跨实例的任务读取）");
        assertTrue(setValues.contains(TaskStatus.CANCELLED.name()), "非终态任务应置为 CANCELLED");
    }

    @Test
    void cancelTask_shouldReturnFalse_whenTaskUnknown() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertFalse(repository.cancelTask(RuntimeContext.empty(), SESSION_ID, "not-exist"));
    }

    @Test
    void getTask_shouldRebuildFromDatabase_whenFutureLostAfterRestart() {
        // 进程重启后内存里没有 future 了，但库里有终态记录——父智能体仍应拿得到结果
        AiAgentTask record = new AiAgentTask();
        record.setTaskId(TASK_ID);
        record.setSubAgentId(SUB_AGENT);
        record.setStatus(TaskStatus.COMPLETED.name());
        record.setResult("上个进程跑完的结果");
        when(mapper.selectOne(any())).thenReturn(record);

        BackgroundTask task = repository.getTask(RuntimeContext.empty(), SESSION_ID, TASK_ID);

        assertNotNull(task);
        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
        assertEquals("上个进程跑完的结果", task.getResult());
    }

    @Test
    void getTask_shouldExposeRunning_whenOwnedByAnotherPod() {
        // 库里 RUNNING 且本 Pod 没有 future，表示任务可能由其它 Pod 持有，不能误报失败
        AiAgentTask record = new AiAgentTask();
        record.setTaskId(TASK_ID);
        record.setStatus(TaskStatus.RUNNING.name());
        when(mapper.selectOne(any())).thenReturn(record);

        BackgroundTask task = repository.getTask(RuntimeContext.empty(), SESSION_ID, TASK_ID);

        assertEquals(TaskStatus.RUNNING, task.getTaskStatus());
    }

    @Test
    void getTask_shouldReturnNull_whenNeitherMemoryNorDatabaseHasIt() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(repository.getTask(RuntimeContext.empty(), SESSION_ID, "not-exist"));
    }

    @Test
    void remoteSpec_shouldFailFastWithExplicitError() {
        BackgroundTask task = repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID,
            new TaskRunSpec.RemoteTaskRunSpec("http://remote", Map.of(), SUB_AGENT, "input"));

        assertEquals(TaskStatus.FAILED, task.getTaskStatus(), "远程任务在后台管理端无执行场景，应明确失败");
        verify(mapper).updateOwnedStatus(eq(TASK_ID), anyString(), eq(TaskStatus.FAILED.name()),
            any(), isNull(), isNull(), any(), isNull(), eq("remote background task is not supported by admin server"));
    }

    @Test
    void removeTask_shouldOnlyDropMemoryReference() {
        repository.putTask(RuntimeContext.empty(), TASK_ID, SUB_AGENT, SESSION_ID, localSpec(() -> "x"));
        assertEquals(1, repository.activeTaskCount());

        repository.removeTask(RuntimeContext.empty(), SESSION_ID, TASK_ID);

        assertEquals(0, repository.activeTaskCount());
        // 库记录是管理台要看的历史，不能跟着删
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
    }
}
