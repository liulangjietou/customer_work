package com.richard.fyoung.customeradmin.ops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelAssetService;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeTrialModelServiceTest {
    private final ModelConfigAccess access = mock(ModelConfigAccess.class);
    private final ModelAssetService assets = mock(ModelAssetService.class);
    private final SecretRefService secrets = mock(SecretRefService.class);
    private final AdminModelFactory factory = mock(AdminModelFactory.class);
    private final KnowledgeTrialModelService service = new KnowledgeTrialModelService(access, assets, secrets, factory);
    private AiModelConfig config;

    @BeforeEach
    void setUp() {
        TenantContext.set("TenantA");
        config = new AiModelConfig(); config.setTenantId("TenantA"); config.setId(7L); config.setProvider("openai");
        config.setModel("frozen-model"); config.setBaseUrl("https://models.example.com/v1");
        config.setEndpointRevision(3); config.setApiKey("cipher-test-only"); config.setSecretRefId(9L);
        when(access.findVisibleById(7L)).thenReturn(config);
        when(assets.findDeclaredContextWindow(config)).thenReturn(32000);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"other", "tenanta", "DEFAULT"})
    void rejectsForeignAndCaseVariantModelOwnershipBeforeReadingAssetsOrCredentials(String tenant) {
        config.setTenantId(tenant);
        assertThrows(BizException.class, () -> service.freeze(7L));
        verifyNoInteractions(assets, secrets, factory);
    }

    @Test
    void retainsExplicitSharedDefaultDeployment() {
        config.setTenantId(TenantContext.DEFAULT);
        service.freeze(7L);
        verify(assets).findDeclaredContextWindow(config);
    }

    @Test
    void buildsOnlyFrozenValuesEvenIfTheMutableConfigChangesDuringSecretResolution() throws Exception {
        var frozen = service.freeze(7L);
        when(secrets.resolvePlaintext(config)).thenAnswer(invocation -> {
            config.setBaseUrl("https://changed.example.com/v1"); config.setModel("changed-model");
            return "plain-test-only";
        });
        service.build(frozen);
        verify(factory).buildModel("openai", "https://models.example.com/v1", "plain-test-only",
            "frozen-model", 7L, 32000);
        String persisted = new ObjectMapper().writeValueAsString(frozen);
        assertFalse(persisted.contains("cipher-test-only"));
        assertFalse(persisted.contains("plain-test-only"));
        assertFalse(persisted.contains("secretRef"));
    }

    @Test
    void driftIsRejectedBeforeResolvingCredentialsOrCallingTheFactory() {
        var frozen = service.freeze(7L); config.setEndpointRevision(4);
        assertThrows(BizException.class, () -> service.build(frozen));
        verifyNoInteractions(secrets, factory);
    }

    @Test
    void changedContextWindowInvalidatesAnOtherwiseIdenticalDeployment() {
        var frozen = service.freeze(7L);
        when(assets.findDeclaredContextWindow(config)).thenReturn(64000);
        assertThrows(BizException.class, () -> service.requireCurrent(frozen));
        verifyNoInteractions(secrets, factory);
    }

    @Test
    void invisibleOrDisabledDeploymentCannotBeFrozenOrUsedAtPublishTime() {
        var frozen = service.freeze(7L);
        when(access.findVisibleById(7L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.freeze(7L));
        assertThrows(BizException.class, () -> service.requireCurrent(frozen));
        verifyNoInteractions(secrets, factory);
    }
}
