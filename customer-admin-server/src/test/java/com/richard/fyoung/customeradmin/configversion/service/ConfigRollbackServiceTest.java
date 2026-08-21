package com.richard.fyoung.customeradmin.configversion.service;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.entity.AiConfigVersion;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.entity.PublishScope;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 配置回滚的租户发布目标与快照校验测试。 */
class ConfigRollbackServiceTest {

    private ConfigVersionService versionService;
    private CustomerWorkConfigPublisher publisher;
    private TenantService tenantService;
    private ConfigRollbackService service;

    @BeforeEach
    void setUp() {
        versionService = mock(ConfigVersionService.class);
        publisher = mock(CustomerWorkConfigPublisher.class);
        tenantService = mock(TenantService.class);
        service = new ConfigRollbackService(versionService, publisher, tenantService);
        when(publisher.isEnabled()).thenReturn(true);
    }

    @Test
    void rollback_shouldRouteFullSnapshotThroughTenantAwarePublisher() {
        AiConfigVersion target = version(PublishScope.FULL, null,
            "customer-work-runtime-config-tenant-tenant-a");
        when(versionService.requireVersion(1L)).thenReturn(target);
        AiConfigVersion current = new AiConfigVersion();
        current.setVersion(4);
        when(versionService.findCurrent(ConfigType.AGENT, "agent-a")).thenReturn(Optional.of(current));

        int version = service.rollback(1L, null);

        assertEquals(4, version);
        verify(publisher).publishRollbackToCurrentTenant(
            "agent-a", 10L, "{\"prompt\":\"old\"}",
            "customer-work-runtime-config-tenant-tenant-a", PublishScope.FULL,
            List.of(), 2, "回滚至 v2");
    }

    @Test
    void rollback_shouldPassGrayTenantEvidenceToPublisher() {
        AiConfigVersion target = version(PublishScope.GRAY, "[\"tenant-a\",\"tenant-b\"]",
            "customer-work-runtime-config-tenant-tenant-a");
        when(versionService.requireVersion(1L)).thenReturn(target);
        when(versionService.findCurrent(ConfigType.AGENT, "agent-a")).thenReturn(Optional.empty());

        int version = service.rollback(1L, "restore gray");

        assertEquals(2, version);
        verify(publisher).publishRollbackToCurrentTenant(
            "agent-a", 10L, "{\"prompt\":\"old\"}",
            "customer-work-runtime-config-tenant-tenant-a", PublishScope.GRAY,
            List.of("tenant-a", "tenant-b"), 2, "restore gray");
    }

    @Test
    void rollback_shouldRejectEmptySnapshotBeforePublishing() {
        AiConfigVersion target = version(PublishScope.FULL, null, "customer-work-runtime-config");
        target.setContent(" ");
        when(versionService.requireVersion(1L)).thenReturn(target);

        BizException exception = assertThrows(BizException.class, () -> service.rollback(1L, null));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
        verify(publisher).isEnabled();
        verify(publisher, never()).publishRollbackToCurrentTenant(
            anyString(), any(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void grayRelease_shouldRejectLegacyPlatformTenantBeforePublishing() {
        when(versionService.requireVersion(1L)).thenReturn(
            version(PublishScope.FULL, null, "customer-work-runtime-config"));

        BizException exception = assertThrows(BizException.class,
            () -> service.grayRelease(1L, List.of("__platform__"), null));

        assertEquals(ResultCode.TENANT_NOT_FOUND, exception.getResultCode());
        verify(tenantService).resolveAccessibleCode("__platform__");
        verify(publisher, never()).publishToDataId(anyString(), anyString());
        verify(versionService, never()).recordPublish(any(), anyString(), any(), anyString(),
            anyString(), any(), anyString(), any(), any());
    }

    private AiConfigVersion version(PublishScope scope, String grayTenants, String dataId) {
        AiConfigVersion version = new AiConfigVersion();
        version.setId(1L);
        version.setConfigType(ConfigType.AGENT.name());
        version.setTargetCode("agent-a");
        version.setTargetId(10L);
        version.setVersion(2);
        version.setContent("{\"prompt\":\"old\"}");
        version.setPublishScope(scope.name());
        version.setGrayTenants(grayTenants);
        version.setDataId(dataId);
        return version;
    }
}
