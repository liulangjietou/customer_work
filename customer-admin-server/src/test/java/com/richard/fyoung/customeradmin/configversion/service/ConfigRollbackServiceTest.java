package com.richard.fyoung.customeradmin.configversion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishIntent;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService.SafePublishCommand;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigPublishOperationResult;
import com.richard.fyoung.customeradmin.configversion.entity.AiConfigVersion;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.entity.PublishScope;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 配置安全回滚的白名单、跨租户预检与可靠入队测试。 */
class ConfigRollbackServiceTest {

    private ConfigVersionService versionService;
    private CustomerWorkConfigPublisher publisher;
    private RuntimePublishTaskService taskService;
    private TenantService tenantService;
    private ConfigRollbackService service;

    @BeforeEach
    void setUp() {
        versionService = mock(ConfigVersionService.class);
        publisher = mock(CustomerWorkConfigPublisher.class);
        taskService = mock(RuntimePublishTaskService.class);
        tenantService = mock(TenantService.class);
        service = new ConfigRollbackService(versionService, publisher, taskService, tenantService,
            new RuntimeRollbackPatchExtractor(new ObjectMapper()));
        when(publisher.isEnabled()).thenReturn(true);
        TenantContext.set("tenant-source");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rollback_shouldQueueWhitelistPatchAgainstCurrentTenantAssets() {
        AiConfigVersion source = version(PublishScope.FULL);
        when(versionService.requireVersion(1L)).thenReturn(source);
        when(publisher.validateSafePublishCandidate(
            anyString(), anyString(), any(RuntimePublishIntent.class))).thenReturn(88L);
        when(taskService.enqueueSafe(any())).thenAnswer(invocation -> {
            SafePublishCommand command = invocation.getArgument(0);
            return List.of(task("task-1", command.operationId(), "tenant-source", 88L,
                command.publishIntent()));
        });

        ConfigPublishOperationResult result = service.rollback(1L, null);

        assertEquals("SAFE_ROLLBACK", result.publishIntent());
        assertEquals("PENDING", result.status());
        assertEquals(1, result.tasks().size());
        ArgumentCaptor<String> patchCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).validateSafePublishCandidate(
            org.mockito.ArgumentMatchers.eq("agent-a"), patchCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(RuntimePublishIntent.SAFE_ROLLBACK));
        String patch = patchCaptor.getValue();
        assertTrue(patch.contains("old prompt"));
        assertTrue(patch.contains("\"maxIters\":7"));
        assertFalse(patch.contains("OLD_SECRET"));
        assertFalse(patch.contains("old.example"));

        ArgumentCaptor<SafePublishCommand> commandCaptor =
            ArgumentCaptor.forClass(SafePublishCommand.class);
        verify(taskService).enqueueSafe(commandCaptor.capture());
        SafePublishCommand command = commandCaptor.getValue();
        assertEquals(source.getId(), command.sourceConfigVersionId());
        assertEquals(source.getContentHash(), command.sourceContentHash());
        assertEquals("tenant-source", command.targets().get(0).tenantId());
        assertEquals(88L, command.targets().get(0).agentId());
    }

    @Test
    void grayRelease_shouldPreflightEveryTenantThenQueueOneAtomicBatch() {
        AiConfigVersion source = version(PublishScope.FULL);
        when(versionService.requireVersion(1L)).thenReturn(source);
        when(tenantService.resolveAccessibleCode("tenant-a")).thenReturn("tenant-a");
        when(tenantService.resolveAccessibleCode("tenant-b")).thenReturn("Tenant-B");
        when(publisher.validateSafePublishCandidate(anyString(), anyString(),
            org.mockito.ArgumentMatchers.eq(RuntimePublishIntent.SAFE_GRAY)))
            .thenAnswer(invocation -> "tenant-a".equals(TenantContext.get()) ? 11L : 22L);
        when(taskService.enqueueSafe(any())).thenAnswer(invocation -> {
            SafePublishCommand command = invocation.getArgument(0);
            return List.of(
                task("task-a", command.operationId(), "tenant-a", 11L, command.publishIntent()),
                task("task-b", command.operationId(), "Tenant-B", 22L, command.publishIntent()));
        });

        ConfigPublishOperationResult result = service.grayRelease(
            1L, List.of("tenant-a", "tenant-b", "tenant-a"), "canary");

        assertEquals(2, result.tasks().size());
        assertEquals("tenant-source", TenantContext.get(), "跨租户预检结束后必须恢复来源租户");
        ArgumentCaptor<SafePublishCommand> captor = ArgumentCaptor.forClass(SafePublishCommand.class);
        verify(taskService).enqueueSafe(captor.capture());
        assertEquals(RuntimePublishIntent.SAFE_GRAY, captor.getValue().publishIntent());
        assertNull(captor.getValue().sourceVersion(),
            "跨租户来源版本号不属于目标租户历史，必须只靠全局 source ID/hash 追溯");
        assertEquals(List.of("tenant-a", "Tenant-B"), captor.getValue().targets().stream()
            .map(RuntimePublishTaskService.SafePublishTarget::tenantId).toList());
        assertEquals("[\"tenant-a\",\"Tenant-B\"]", captor.getValue().grayTenantsJson());
    }

    @Test
    void grayRelease_shouldCreateNoTaskWhenAnyTenantPreflightFails() {
        when(versionService.requireVersion(1L)).thenReturn(version(PublishScope.FULL));
        when(tenantService.resolveAccessibleCode("tenant-a")).thenReturn("tenant-a");
        when(tenantService.resolveAccessibleCode("tenant-b")).thenReturn("tenant-b");
        when(publisher.validateSafePublishCandidate(anyString(), anyString(), any()))
            .thenAnswer(invocation -> {
                if ("tenant-b".equals(TenantContext.get())) {
                    throw new IllegalStateException("current secret unavailable");
                }
                return 11L;
            });

        BizException exception = assertThrows(BizException.class,
            () -> service.grayRelease(1L, List.of("tenant-a", "tenant-b"), null));

        assertEquals(ResultCode.RUNTIME_PUBLISH_FAILED, exception.getResultCode());
        verify(taskService, never()).enqueueSafe(any());
        assertEquals("tenant-source", TenantContext.get());
    }

    @Test
    void rollback_shouldRejectTamperedHistoricalSnapshotBeforePreflight() {
        AiConfigVersion source = version(PublishScope.FULL);
        source.setContent(source.getContent().replace("old prompt", "tampered"));
        when(versionService.requireVersion(1L)).thenReturn(source);

        BizException exception = assertThrows(BizException.class,
            () -> service.rollback(1L, null));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
        verify(publisher, never()).validateSafePublishCandidate(anyString(), anyString(), any());
        verify(taskService, never()).enqueueSafe(any());
    }

    @Test
    void rollback_shouldRejectFailedOrNonAgentSource() {
        AiConfigVersion failed = version(PublishScope.FULL);
        failed.setStatus("FAILED");
        when(versionService.requireVersion(1L)).thenReturn(failed);
        assertEquals(ResultCode.PARAM_INVALID,
            assertThrows(BizException.class, () -> service.rollback(1L, null)).getResultCode());

        AiConfigVersion model = version(PublishScope.FULL);
        model.setConfigType(ConfigType.MODEL.name());
        when(versionService.requireVersion(2L)).thenReturn(model);
        assertEquals(ResultCode.PARAM_INVALID,
            assertThrows(BizException.class, () -> service.rollback(2L, null)).getResultCode());
        verify(taskService, never()).enqueueSafe(any());
    }

    private AiConfigVersion version(PublishScope scope) {
        AiConfigVersion version = new AiConfigVersion();
        version.setId(1L);
        version.setConfigType(ConfigType.AGENT.name());
        version.setTargetCode("agent-a");
        version.setTargetId(10L);
        version.setVersion(2);
        version.setContent("""
            {"systemPrompt":"old prompt","agent":{"maxIters":7},
             "model":{"baseUrl":"https://old.example","apiKeyCipher":"OLD_SECRET"},
             "mcpServers":[{"headers":{"Authorization":"Bearer OLD"}}]}
            """.trim());
        version.setContentHash(sha256(version.getContent()));
        version.setPublishScope(scope.name());
        version.setStatus("PUBLISHED");
        return version;
    }

    private RuntimePublishTask task(String id, String operationId, String tenantId, Long targetId,
                                    RuntimePublishIntent intent) {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId(id);
        task.setOperationId(operationId);
        task.setTenantId(tenantId);
        task.setTargetId(targetId);
        task.setPublishIntent(intent.name());
        task.setStatus("PENDING");
        return task;
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
