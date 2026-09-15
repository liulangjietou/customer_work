package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandOutputEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandResultEvent;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-2/P2-3 命令运行时单测：真实 local 进程、统一护栏、历史运行态与清理。 */
class SandboxCommandServiceTest {

    @TempDir
    Path workspace;

    private final AdminSandboxProperties properties = new AdminSandboxProperties();
    private final SandboxRiskDetector riskDetector = mock(SandboxRiskDetector.class);
    private final AdminAgentInstanceFactory factory = mock(AdminAgentInstanceFactory.class);
    private final AgentWorkspaceManager workspaceManager = mock(AgentWorkspaceManager.class);
    private final AiCodingAuditService auditService = mock(AiCodingAuditService.class);
    private SandboxCommandService service;

    @AfterEach
    void cleanup() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void executeLocal_shouldStreamOutputReturnExitCodeAndExposeIdleSandbox() {
        enableFeatures();
        when(workspaceManager.resolveSessionWorkspace("coder", "s1")).thenReturn(workspace);
        when(auditService.begin(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("coder"), org.mockito.ArgumentMatchers.eq("s1")))
            .thenReturn(new AiCodingAuditLog());
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, workspaceManager);

        List<SandboxCommandEvent> events = service.execute("coder", "s1", 7L,
                "printf 'first\\n'; sleep 0.1; printf 'second\\n'")
            .collectList().block(Duration.ofSeconds(5));

        assertTrue(events.stream().anyMatch(event -> event.payload() instanceof CommandOutputEvent output
            && output.text().contains("first")));
        CommandResultEvent result = events.stream()
            .filter(event -> event.payload() instanceof CommandResultEvent)
            .map(event -> (CommandResultEvent) event.payload())
            .findFirst().orElseThrow();
        assertEquals(0, result.exitCode());
        assertTrue(result.success());
        assertEquals("IDLE", service.list("coder", 7L).get(0).status());
        verify(workspaceManager).persistSessionWorkspace("coder", "s1");

        assertTrue(service.cleanup("coder", "s1", 7L));
        assertFalse(service.cleanup("coder", "s1", 7L));
        assertTrue(service.list("coder", 7L).isEmpty());
    }

    @Test
    void executeLocal_shouldKeepSessionBusyUntilPersistenceFinishes() throws Exception {
        enableFeatures();
        when(workspaceManager.resolveSessionWorkspace("coder", "s1")).thenReturn(workspace);
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch allowPersistence = new CountDownLatch(1);
        AtomicBoolean resultSeen = new AtomicBoolean();
        doAnswer(invocation -> {
            persistenceStarted.countDown();
            assertTrue(allowPersistence.await(5, TimeUnit.SECONDS));
            return null;
        }).when(workspaceManager).persistSessionWorkspace("coder", "s1");
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, workspaceManager);
        var completion = service.execute("coder", "s1", 7L, "printf 'saved'")
            .doOnNext(event -> {
                if (event.payload() instanceof CommandResultEvent) {
                    resultSeen.set(true);
                }
            }).collectList().toFuture();

        try {
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS));
            assertFalse(resultSeen.get(), "持久化结束前不能宣布命令结果");
            assertFalse(completion.isDone(), "持久化结束前不能结束事件流");
            BizException busy = assertThrows(BizException.class,
                () -> service.execute("coder", "s1", 7L, "printf 'next'"));
            assertEquals(ResultCode.SANDBOX_COMMAND_RUNNING, busy.getResultCode());
        } finally {
            allowPersistence.countDown();
            completion.get(5, TimeUnit.SECONDS);
        }
        assertTrue(resultSeen.get());
        assertEquals("IDLE", service.list("coder", 7L).get(0).status());
    }

    @Test
    void executeLocal_shouldPersistTheOriginalSubjectWorkspace() throws Exception {
        enableFeatures();
        var storage = mock(SessionWorkspaceStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionWorkspaceStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        var manager = spy(new AgentWorkspaceManager(provider));
        doAnswer(invocation -> {
            AgentMemoryScope scope = invocation.getArgument(0);
            return workspace.resolve(scope.trusted() ? scope.subjectHash() : "shared");
        }).when(manager).resolveWorkspace(any(AgentMemoryScope.class));
        AtomicReference<Path> savedPath = new AtomicReference<>();
        CountDownLatch saved = new CountDownLatch(1);
        when(storage.preparePersist(eq("coder"), eq("s1"), any())).thenAnswer(invocation -> {
            savedPath.set(invocation.getArgument(2));
            saved.countDown();
            return (BooleanSupplier) () -> true;
        });
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, manager);
        var identity = new AgentInvocationIdentity("tenant-a", QuotaSubjectType.ADMIN_USER, "7", true);
        Path expected = AgentInvocationIdentityContext.callWith(identity,
            () -> manager.resolveSessionWorkspace("coder", "s1"));
        var stream = AgentInvocationIdentityContext.callWith(identity,
            () -> service.execute("coder", "s1", 7L, "printf 'owned' > result.txt"));

        stream.collectList().block(Duration.ofSeconds(5));
        assertTrue(saved.await(5, TimeUnit.SECONDS));
        assertEquals(expected, savedPath.get(), "异步保存必须与执行命令的主体目录一致");
        assertEquals("owned", Files.readString(expected.resolve("result.txt")));
    }

    @Test
    void executeLocal_shouldReportPersistenceExceptionBeforeCompleting() {
        enableFeatures();
        when(workspaceManager.resolveSessionWorkspace("coder", "s1")).thenReturn(workspace);
        doThrow(new IllegalStateException("persistence unavailable"))
            .when(workspaceManager).persistSessionWorkspace("coder", "s1");
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, workspaceManager);

        BizException error = assertThrows(BizException.class, () -> service.execute("coder", "s1", 7L, "printf 'saved'")
            .collectList().block(Duration.ofSeconds(5)));

        assertEquals(ResultCode.SANDBOX_RUNTIME_FAILED, error.getResultCode());
        assertEquals("FAILED", service.list("coder", 7L).get(0).status());
        verify(auditService).finish(any(), any(Throwable.class));
        verify(auditService, never()).finish(any(), nullable(String.class));
    }

    @Test
    void executeLocal_shouldFinishPersistenceWhenCleanedBeforeSubscription() {
        enableFeatures();
        when(workspaceManager.resolveSessionWorkspace("coder", "s1")).thenReturn(workspace);
        AtomicBoolean persisted = new AtomicBoolean();
        doAnswer(invocation -> {
            persisted.set(true);
            return null;
        })
            .when(workspaceManager).persistSessionWorkspace("coder", "s1");
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, workspaceManager);
        var stream = service.execute("coder", "s1", 7L, "printf 'cancelled'");
        assertTrue(service.cleanup("coder", "s1", 7L));

        var events = stream.collectList().doOnSuccess(ignored ->
            assertTrue(persisted.get(), "取消分支也必须先收尾再结束事件流"))
            .block(Duration.ofSeconds(5));

        assertTrue(events.isEmpty());
    }

    @Test
    void executeLocal_shouldPreserveNonzeroExitAfterPersistence() {
        enableFeatures();
        when(workspaceManager.resolveSessionWorkspace("coder", "s1")).thenReturn(workspace);
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, workspaceManager);

        var events = service.execute("coder", "s1", 7L, "exit 7")
            .collectList().block(Duration.ofSeconds(5));
        var result = events.stream().filter(event -> event.payload() instanceof CommandResultEvent)
            .map(event -> (CommandResultEvent) event.payload()).findFirst().orElseThrow();

        assertEquals(7, result.exitCode());
        assertFalse(result.success());
        verify(workspaceManager).persistSessionWorkspace("coder", "s1");
        assertEquals("IDLE", service.list("coder", 7L).get(0).status());
    }

    @Test
    void execute_shouldRejectDestructiveCommandThroughSharedDetector() {
        enableFeatures();
        when(workspaceManager.resolveSessionWorkspace("coder", "s1")).thenReturn(workspace);
        when(riskDetector.matchesDestructive("rm -rf .")).thenReturn(true);
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, workspaceManager);

        BizException error = assertThrows(BizException.class,
            () -> service.execute("coder", "s1", 7L, "rm -rf ."));

        assertEquals(ResultCode.SANDBOX_COMMAND_BLOCKED, error.getResultCode());
    }

    @Test
    void execute_shouldFailClosedWhenFeatureNotEnabled() {
        service = new SandboxCommandService(properties, riskDetector, factory, auditService, workspaceManager);

        BizException error = assertThrows(BizException.class,
            () -> service.execute("coder", "s1", 7L, "mvn test"));

        assertEquals(ResultCode.AI_CODING_FEATURE_DISABLED, error.getResultCode());
    }

    private void enableFeatures() {
        properties.getFeatures().setCommandExecutionEnabled(true);
        properties.getFeatures().setManagementEnabled(true);
    }
}
