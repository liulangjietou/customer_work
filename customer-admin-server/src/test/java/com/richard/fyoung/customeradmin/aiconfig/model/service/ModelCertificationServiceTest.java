package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelCertificationCheckStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelCertificationStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationCheckVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertificationRun;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationRunMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.ModelCertificationProbe;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.service.ModelPriceService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCertificationServiceTest {

    private ModelConfigAccess modelConfigAccess;
    private ModelAssetService modelAssetService;
    private SecretRefService secretRefService;
    private ModelCertificationProbe certificationProbe;
    private ModelPriceService modelPriceService;
    private ModelCertificationStore certificationStore;
    private AiModelCertificationMapper certificationMapper;
    private AiModelCertificationRunMapper runMapper;
    private ModelCertificationService service;
    private AiModelConfig model;
    private SecretMetadataVO secret;

    @BeforeEach
    void setUp() {
        modelConfigAccess = mock(ModelConfigAccess.class);
        modelAssetService = mock(ModelAssetService.class);
        secretRefService = mock(SecretRefService.class);
        certificationProbe = mock(ModelCertificationProbe.class);
        modelPriceService = mock(ModelPriceService.class);
        certificationStore = mock(ModelCertificationStore.class);
        certificationMapper = mock(AiModelCertificationMapper.class);
        runMapper = mock(AiModelCertificationRunMapper.class);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(false);
        service = new ModelCertificationService(modelConfigAccess, modelAssetService, secretRefService,
            certificationProbe, modelPriceService, certificationStore, certificationMapper, runMapper,
            tenantProperties, mock(CrossTenantAuthority.class), new ObjectMapper());

        model = new AiModelConfig();
        model.setId(1L);
        model.setTenantId("default");
        model.setAssetId(10L);
        model.setSecretRefId(20L);
        model.setProvider("openai");
        model.setModel("gpt-test");
        model.setEndpointRevision(3);
        model.setCertificationRequired(1);
        secret = new SecretMetadataVO(20L, "ref", "LOCAL_AES", 4, "ACTIVE",
            LocalDateTime.now().plusDays(7), LocalDateTime.now(), 1L);
        when(modelConfigAccess.findVisibleAnyStateById(1L)).thenReturn(model);
        when(modelAssetService.requireVisible(10L, "default", false)).thenReturn(new AiModelAsset());
        when(secretRefService.metadata(20L, "default")).thenReturn(secret);
        when(secretRefService.resolvePlaintext(model)).thenReturn("secret-value");
        when(certificationStore.record(any(AiModelCertificationRun.class), anyInt(), anyInt(), any()))
            .thenAnswer(invocation -> {
                AiModelCertificationRun run = invocation.getArgument(0);
                return new ModelCertificationStore.RecordResult(run, true);
            });
    }

    @Test
    void certify_shouldPersistPassedRunAndCapValidityAtSecretExpiry() {
        when(certificationProbe.probe(eq(model), any(), eq("secret-value"), any()))
            .thenReturn(new ModelCertificationProbe.ProbeResult(List.of(
                check("CONNECTIVITY", ModelCertificationCheckStatus.PASSED.name())), 120L, 32000));
        AiModelPrice price = price("2.5", "10");
        when(modelPriceService.findEffectivePrice(eq("openai"), eq("gpt-test"), any())).thenReturn(price);

        ModelCertificationVO result = service.certify(1L, request());

        assertEquals(ModelCertificationStatus.PASSED.name(), result.getStatus());
        assertEquals(ModelCertificationStatus.PASSED.name(), result.getEffectiveStatus());
        assertEquals(3, result.getPassedChecks());
        assertEquals(0, result.getFailedChecks());
        assertNotNull(result.getValidUntil());
        assertTrue(!result.getValidUntil().isAfter(secret.expiresAt()));
    }

    @Test
    void certify_shouldFailClosed_whenPriceIsMissing() {
        when(certificationProbe.probe(eq(model), any(), eq("secret-value"), any()))
            .thenReturn(new ModelCertificationProbe.ProbeResult(List.of(
                check("CONNECTIVITY", ModelCertificationCheckStatus.PASSED.name())), 120L, 32000));
        when(modelPriceService.findEffectivePrice(eq("openai"), eq("gpt-test"), any())).thenReturn(null);

        ModelCertificationVO result = service.certify(1L, request());

        assertEquals(ModelCertificationStatus.FAILED.name(), result.getStatus());
        assertEquals(2, result.getFailedChecks());
        assertEquals("INPUT_COST", result.getFailureCode());
    }

    @Test
    void strictRouteGate_shouldRejectEndpointRevisionDrift() {
        AiModelCertification snapshot = new AiModelCertification();
        snapshot.setCurrentRunId(88L);
        when(certificationMapper.selectOne(any(QueryWrapper.class))).thenReturn(snapshot);
        AiModelCertificationRun run = new AiModelCertificationRun();
        run.setId(88L);
        run.setStatus(ModelCertificationStatus.PASSED.name());
        run.setEndpointRevision(2);
        run.setSecretVersion(4);
        run.setValidUntil(LocalDateTime.now().plusDays(1));
        when(runMapper.selectOne(any(QueryWrapper.class))).thenReturn(run);

        assertThrows(BizException.class, () -> service.requirePassedCurrent(model));
    }

    @Test
    void certify_shouldReturnStaleAndNotClaimPromotion_whenConfigurationChangesDuringSlowProbe() {
        AiModelConfig changed = new AiModelConfig();
        changed.setId(model.getId());
        changed.setTenantId(model.getTenantId());
        changed.setAssetId(model.getAssetId());
        changed.setSecretRefId(model.getSecretRefId());
        changed.setProvider(model.getProvider());
        changed.setModel(model.getModel());
        changed.setEndpointRevision(4);
        changed.setCertificationRequired(1);
        when(modelConfigAccess.findVisibleAnyStateById(1L)).thenReturn(model, changed);
        when(certificationProbe.probe(eq(model), any(), eq("secret-value"), any()))
            .thenReturn(new ModelCertificationProbe.ProbeResult(List.of(
                check("CONNECTIVITY", ModelCertificationCheckStatus.PASSED.name())), 120L, 32000));
        when(modelPriceService.findEffectivePrice(eq("openai"), eq("gpt-test"), any()))
            .thenReturn(price("2.5", "10"));
        when(certificationStore.record(any(AiModelCertificationRun.class), anyInt(), anyInt(), eq(20L)))
            .thenAnswer(invocation -> new ModelCertificationStore.RecordResult(invocation.getArgument(0), false));

        ModelCertificationVO result = service.certify(1L, request());

        assertEquals(ModelCertificationStatus.PASSED.name(), result.getStatus());
        assertEquals(ModelCertificationStatus.STALE.name(), result.getEffectiveStatus());
        assertTrue(result.getStaleReason().contains("未晋级"));
    }

    private ModelCertificationRequest request() {
        return new ModelCertificationRequest(8192, 1000L, new BigDecimal("5"),
            new BigDecimal("20"), 30, true, true, true);
    }

    private ModelCertificationCheckVO check(String code, String status) {
        return new ModelCertificationCheckVO(code, code, status, "ok", "ok", "通过");
    }

    private AiModelPrice price(String input, String output) {
        AiModelPrice price = new AiModelPrice();
        price.setInputPrice(new BigDecimal(input));
        price.setOutputPrice(new BigDecimal(output));
        price.setCurrency("CNY");
        return price;
    }
}
