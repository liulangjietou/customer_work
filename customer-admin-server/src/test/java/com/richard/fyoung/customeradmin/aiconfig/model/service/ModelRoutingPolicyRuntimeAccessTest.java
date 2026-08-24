package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRoutePolicyStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRouteVersionStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicy;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicyVersion;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRouteRule;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRouteRuleMapper;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingPolicyRuntimeAccessTest {

    private AiModelRoutePolicyMapper policyMapper;
    private AiModelRoutePolicyVersionMapper versionMapper;
    private AiModelRouteRuleMapper ruleMapper;
    private ModelConfigAccess modelConfigAccess;
    private SecretRefService secretRefService;
    private ModelHealthRuntimeAccess healthRuntimeAccess;
    private AdminTenantProperties tenantProperties;
    private ModelRoutingPolicyRuntimeAccess access;

    @BeforeEach
    void setUp() {
        policyMapper = mock(AiModelRoutePolicyMapper.class);
        versionMapper = mock(AiModelRoutePolicyVersionMapper.class);
        ruleMapper = mock(AiModelRouteRuleMapper.class);
        modelConfigAccess = mock(ModelConfigAccess.class);
        secretRefService = mock(SecretRefService.class);
        healthRuntimeAccess = mock(ModelHealthRuntimeAccess.class);
        tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(true);
        access = new ModelRoutingPolicyRuntimeAccess(policyMapper, versionMapper, ruleMapper,
            modelConfigAccess, secretRefService, tenantProperties, new ObjectMapper(),
            healthRuntimeAccess);
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void requireActive_shouldFreezeTenantScopedActiveSnapshotWithCurrentSecretCipher() {
        AiModelRoutePolicy policy = activePolicy();
        AiModelRoutePolicyVersion version = activeVersion();
        AiModelRouteRule rule = defaultRule();
        AiModelConfig deployment = deployment();
        when(policyMapper.selectOne(any(QueryWrapper.class))).thenReturn(policy);
        when(versionMapper.selectOne(any(QueryWrapper.class))).thenReturn(version);
        when(ruleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rule));
        when(modelConfigAccess.findVisibleById(10L)).thenReturn(deployment);
        when(secretRefService.resolveCipherText(91L, "tenant-a", "LEGACY_CIPHER"))
            .thenReturn("CURRENT_SECRET_CIPHER");
        CustomerWorkRuntimeConfig.HealthOverlay health = new CustomerWorkRuntimeConfig.HealthOverlay();
        health.setEffectiveHealthStatus("UNHEALTHY");
        health.setRoutingAvailable(false);
        when(healthRuntimeAccess.overlay(deployment)).thenReturn(health);

        CustomerWorkRuntimeConfig.RoutingPolicy runtime =
            access.requireActive(1L, 7L, "webchat");

        assertEquals(1L, runtime.getPolicyId());
        assertEquals(11L, runtime.getVersionId());
        assertEquals("route-hash", runtime.getPolicyContentHash());
        assertEquals(7L, runtime.getAgentId());
        assertEquals("webchat", runtime.getChannelCode());
        assertEquals("CURRENT_SECRET_CIPHER", runtime.getDeployments().get(0).getApiKeyCipher());
        assertEquals("UNHEALTHY",
            runtime.getDeployments().get(0).getHealth().getEffectiveHealthStatus());
        assertTrue(!runtime.getDeployments().get(0).getHealth().isRoutingAvailable());
        assertEquals(List.of(7L), runtime.getRules().get(0).getCondition().getAgentIds());

        ArgumentCaptor<QueryWrapper<AiModelRoutePolicy>> policyQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(policyMapper).selectOne(policyQuery.capture());
        assertTrue(policyQuery.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(policyQuery.getValue().getParamNameValuePairs().containsValue("tenant-a"));

        ArgumentCaptor<QueryWrapper<AiModelRoutePolicyVersion>> versionQuery =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(versionMapper).selectOne(versionQuery.capture());
        assertTrue(versionQuery.getValue().getSqlSegment().contains("status"));
        assertTrue(versionQuery.getValue().getParamNameValuePairs()
            .containsValue(ModelRouteVersionStatus.ACTIVE.name()));
    }

    @Test
    void requireActive_shouldFailClosedWithoutTaskTenantContext() {
        TenantContext.clear();

        BizException error = assertThrows(BizException.class,
            () -> access.requireActive(1L, 7L, "webchat"));

        assertEquals(ResultCode.FORBIDDEN, error.getResultCode());
        verify(policyMapper, never()).selectOne(any(QueryWrapper.class));
    }

    @Test
    void requireActive_shouldRejectInactivePolicyBeforeReadingCredentials() {
        AiModelRoutePolicy policy = activePolicy();
        policy.setStatus(ModelRoutePolicyStatus.DRAFT.name());
        when(policyMapper.selectOne(any(QueryWrapper.class))).thenReturn(policy);

        BizException error = assertThrows(BizException.class,
            () -> access.requireActive(1L, 7L, "webchat"));
        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        verify(secretRefService, never()).resolveCipherText(any(), any(), any());
    }

    @Test
    void requireActive_shouldRejectDeploymentThatFailsRuntimeCertificationGate() {
        when(policyMapper.selectOne(any(QueryWrapper.class))).thenReturn(activePolicy());
        when(versionMapper.selectOne(any(QueryWrapper.class))).thenReturn(activeVersion());
        when(ruleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(defaultRule()));
        when(modelConfigAccess.findVisibleById(10L)).thenReturn(null);

        BizException error = assertThrows(BizException.class,
            () -> access.requireActive(1L, 7L, "webchat"));
        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        verify(secretRefService, never()).resolveCipherText(any(), any(), any());
    }

    private AiModelRoutePolicy activePolicy() {
        AiModelRoutePolicy policy = new AiModelRoutePolicy();
        policy.setId(1L);
        policy.setTenantId("tenant-a");
        policy.setStatus(ModelRoutePolicyStatus.ACTIVE.name());
        policy.setCurrentVersionId(11L);
        return policy;
    }

    private AiModelRoutePolicyVersion activeVersion() {
        AiModelRoutePolicyVersion version = new AiModelRoutePolicyVersion();
        version.setId(11L);
        version.setTenantId("tenant-a");
        version.setPolicyId(1L);
        version.setVersionNo(3);
        version.setStatus(ModelRouteVersionStatus.ACTIVE.name());
        version.setContentHash("route-hash");
        return version;
    }

    private AiModelRouteRule defaultRule() {
        AiModelRouteRule rule = new AiModelRouteRule();
        rule.setId(21L);
        rule.setTenantId("tenant-a");
        rule.setPolicyVersionId(11L);
        rule.setPurpose("DEFAULT");
        rule.setDeploymentId(10L);
        rule.setPriority(100);
        rule.setConditionJson("{\"agentIds\":[7],\"channelCodes\":[\"webchat\"]}");
        return rule;
    }

    private AiModelConfig deployment() {
        AiModelConfig deployment = new AiModelConfig();
        deployment.setId(10L);
        deployment.setTenantId("tenant-a");
        deployment.setProvider("openai");
        deployment.setModel("gpt-4o");
        deployment.setBaseUrl("https://model.example");
        deployment.setEndpointRevision(4);
        deployment.setSecretRefId(91L);
        deployment.setApiKey("LEGACY_CIPHER");
        return deployment;
    }
}
