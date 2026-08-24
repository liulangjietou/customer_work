package com.richard.fyoung.customeradmin.governance.change.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigPublishOperationResult;
import com.richard.fyoung.customeradmin.configversion.service.ConfigRollbackService;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeType;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigRollbackGovernedChangeExecutorTest {

    private ConfigRollbackService rollbackService;
    private ConfigRollbackGovernedChangeExecutor executor;

    @BeforeEach
    void setUp() {
        rollbackService = mock(ConfigRollbackService.class);
        executor = new ConfigRollbackGovernedChangeExecutor(rollbackService, new ObjectMapper());
    }

    @Test
    void rollbackCommand_shouldRouteTypedPayloadToRollbackService() {
        ConfigPublishOperationResult expected = result("rollback-op");
        when(rollbackService.rollback(42L, "incident rollback")).thenReturn(expected);
        AiGovernedChangeRequest request = request(GovernedChangeType.CONFIG_ROLLBACK,
            "{\"versionId\":42,\"remark\":\"incident rollback\"}");

        Object actual = executor.execute(request);

        assertSame(expected, actual);
        assertEquals(Set.of(GovernedChangeType.CONFIG_ROLLBACK,
            GovernedChangeType.CONFIG_GRAY_RELEASE), executor.types());
        verify(rollbackService).rollback(42L, "incident rollback");
    }

    @Test
    void grayReleaseCommand_shouldPreserveTenantListAndRemark() {
        ConfigPublishOperationResult expected = result("gray-op");
        when(rollbackService.grayRelease(43L, List.of("tenant-a", "tenant-b"), "canary"))
            .thenReturn(expected);
        AiGovernedChangeRequest request = request(GovernedChangeType.CONFIG_GRAY_RELEASE,
            "{\"versionId\":43,\"tenantCodes\":[\"tenant-a\",\"tenant-b\"],"
                + "\"remark\":\"canary\"}");

        assertSame(expected, executor.execute(request));

        verify(rollbackService).grayRelease(
            43L, List.of("tenant-a", "tenant-b"), "canary");
    }

    @Test
    void malformedPayload_shouldFailBeforeCallingDownstreamService() {
        AiGovernedChangeRequest request = request(
            GovernedChangeType.CONFIG_ROLLBACK, "{not-json}");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class, () -> executor.execute(request));

        assertEquals("invalid governed config command", failure.getMessage());
    }

    private AiGovernedChangeRequest request(GovernedChangeType type, String payload) {
        AiGovernedChangeRequest request = new AiGovernedChangeRequest();
        request.setChangeType(type.name());
        request.setPayloadJson(payload);
        return request;
    }

    private ConfigPublishOperationResult result(String operationId) {
        return new ConfigPublishOperationResult(
            operationId, "SAFE", "PENDING", 42L, "hash", List.of());
    }
}
