package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandOutputEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandResultEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
