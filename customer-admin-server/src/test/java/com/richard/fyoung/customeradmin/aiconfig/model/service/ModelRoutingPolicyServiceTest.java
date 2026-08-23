package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRoutePolicyStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRouteVersionStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteDryRunRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteDryRunVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicy;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicyVersion;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRouteRule;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRouteRuleMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingPolicyServiceTest {

    private AiModelRoutePolicyMapper policyMapper;
    private AiModelRoutePolicyVersionMapper versionMapper;
    private AiModelRouteRuleMapper ruleMapper;
    private ModelConfigAccess modelConfigAccess;
    private ModelCertificationService certificationService;
    private AiAgentMapper agentMapper;
    private AgentInstanceCache agentInstanceCache;
    private CustomerWorkConfigPublisher runtimeConfigPublisher;
    private ModelRoutingPolicyService service;

    @BeforeEach
    void setUp() {
        policyMapper = mock(AiModelRoutePolicyMapper.class);
        versionMapper = mock(AiModelRoutePolicyVersionMapper.class);
        ruleMapper = mock(AiModelRouteRuleMapper.class);
        modelConfigAccess = mock(ModelConfigAccess.class);
        certificationService = mock(ModelCertificationService.class);
        agentMapper = mock(AiAgentMapper.class);
        agentInstanceCache = mock(AgentInstanceCache.class);
        runtimeConfigPublisher = mock(CustomerWorkConfigPublisher.class);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(false);
        service = new ModelRoutingPolicyService(policyMapper, versionMapper, ruleMapper,
            new ModelRouteRuleValidator(), modelConfigAccess, certificationService, tenantProperties,
            mock(CrossTenantAuthority.class), new ObjectMapper(), agentMapper,
            agentInstanceCache, runtimeConfigPublisher);
    }

    @Test
    void dryRun_shouldFailClosed_whenDegradeHasNoFallbackCandidate() {
        AiModelRoutePolicy policy = activePolicy();
        AiModelRoutePolicyVersion version = version();
        when(policyMapper.selectById(1L)).thenReturn(policy);
        when(versionMapper.selectOne(any(QueryWrapper.class))).thenReturn(version);
        when(ruleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(defaultRule()));

        ModelRouteDryRunVO result = service.dryRun(1L,
            new ModelRouteDryRunRequest(null, null, 100, false, false, "LOW", true));

        assertFalse(result.isMatched());
        assertTrue(result.isFailClosed());
        assertTrue(result.getExplanation().contains("DEGRADE"));
    }

    @Test
    void activate_shouldRequireEveryDeploymentCertifiedBeforePublishingVersion() {
        AiModelRoutePolicy policy = activePolicy();
        policy.setStatus(ModelRoutePolicyStatus.DRAFT.name());
        policy.setCurrentVersionId(null);
        AiModelRoutePolicyVersion version = version();
        AiModelRouteRule rule = defaultRule();
        AiModelConfig deployment = new AiModelConfig();
        deployment.setId(10L);
        deployment.setStatus(1);
        deployment.setLifecycleStatus(ModelDeploymentLifecycle.ACTIVE.name());
        when(policyMapper.selectOne(any(QueryWrapper.class))).thenReturn(policy);
        when(policyMapper.selectById(1L)).thenReturn(policy);
        when(versionMapper.selectOne(any(QueryWrapper.class))).thenReturn(version);
        when(ruleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rule));
        when(modelConfigAccess.findVisibleAnyStateById(10L)).thenReturn(deployment);

        service.activate(1L, 11L);

        verify(certificationService).requirePassedCurrent(deployment);
        verify(versionMapper).update(isNull(), any(UpdateWrapper.class));
        verify(policyMapper).updateById(policy);
    }

    @Test
    void activate_shouldEvictAndRepublishEveryEnabledBoundAgent() {
        AiModelRoutePolicy policy = activePolicy();
        policy.setStatus(ModelRoutePolicyStatus.DRAFT.name());
        policy.setCurrentVersionId(null);
        AiModelRoutePolicyVersion version = version();
        AiModelConfig deployment = new AiModelConfig();
        deployment.setId(10L);
        deployment.setStatus(1);
        deployment.setLifecycleStatus(ModelDeploymentLifecycle.ACTIVE.name());
        when(policyMapper.selectOne(any(QueryWrapper.class))).thenReturn(policy);
        when(policyMapper.selectById(1L)).thenReturn(policy);
        when(versionMapper.selectOne(any(QueryWrapper.class))).thenReturn(version);
        when(ruleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(defaultRule()));
        when(modelConfigAccess.findVisibleAnyStateById(10L)).thenReturn(deployment);
        com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent first =
            boundAgent(101L, "support-a");
        com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent second =
            boundAgent(102L, "support-b");
        when(agentMapper.selectList(any())).thenReturn(List.of(first, second));

        service.activate(1L, 11L);

        verify(agentInstanceCache).evict("support-a");
        verify(agentInstanceCache).evict("support-b");
        verify(runtimeConfigPublisher).publishForAgentId(101L);
        verify(runtimeConfigPublisher).publishForAgentId(102L);
    }

    private AiModelRoutePolicy activePolicy() {
        AiModelRoutePolicy policy = new AiModelRoutePolicy();
        policy.setId(1L);
        policy.setTenantId("default");
        policy.setPolicyCode("main");
        policy.setPolicyName("主策略");
        policy.setStatus(ModelRoutePolicyStatus.ACTIVE.name());
        policy.setCurrentVersionId(11L);
        policy.setCurrentVersionNo(1);
        policy.setLatestVersionNo(1);
        return policy;
    }

    private AiModelRoutePolicyVersion version() {
        AiModelRoutePolicyVersion version = new AiModelRoutePolicyVersion();
        version.setId(11L);
        version.setTenantId("default");
        version.setPolicyId(1L);
        version.setVersionNo(1);
        version.setStatus(ModelRouteVersionStatus.DRAFT.name());
        version.setContentHash("hash");
        return version;
    }

    private AiModelRouteRule defaultRule() {
        AiModelRouteRule rule = new AiModelRouteRule();
        rule.setId(21L);
        rule.setTenantId("default");
        rule.setPolicyVersionId(11L);
        rule.setPurpose("DEFAULT");
        rule.setDeploymentId(10L);
        rule.setPriority(100);
        rule.setConditionJson("{\"agentIds\":[],\"channelCodes\":[]}");
        rule.setConditionSummary("无条件");
        return rule;
    }

    private com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent boundAgent(Long id, String code) {
        com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent agent =
            new com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent();
        agent.setId(id);
        agent.setAgentCode(code);
        agent.setStatus(1);
        agent.setModelRoutePolicyId(1L);
        return agent;
    }
}
