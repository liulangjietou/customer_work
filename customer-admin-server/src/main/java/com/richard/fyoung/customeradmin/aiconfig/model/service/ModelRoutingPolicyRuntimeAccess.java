package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRoutePolicyStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRouteVersionStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteCondition;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 路由策略运行时快照唯一读取入口。把不可变规则版本与当前可运行部署冻结到同一发布载荷，
 * 并在这里统一执行租户、生命周期、认证、凭据可用性门禁。
 */
@Component
public class ModelRoutingPolicyRuntimeAccess {

    private final AiModelRoutePolicyMapper policyMapper;
    private final AiModelRoutePolicyVersionMapper versionMapper;
    private final AiModelRouteRuleMapper ruleMapper;
    private final ModelConfigAccess modelConfigAccess;
    private final SecretRefService secretRefService;
    private final AdminTenantProperties tenantProperties;
    private final ObjectMapper objectMapper;

    public ModelRoutingPolicyRuntimeAccess(AiModelRoutePolicyMapper policyMapper,
                                           AiModelRoutePolicyVersionMapper versionMapper,
                                           AiModelRouteRuleMapper ruleMapper,
                                           ModelConfigAccess modelConfigAccess,
                                           SecretRefService secretRefService,
                                           AdminTenantProperties tenantProperties,
                                           ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.versionMapper = versionMapper;
        this.ruleMapper = ruleMapper;
        this.modelConfigAccess = modelConfigAccess;
        this.secretRefService = secretRefService;
        this.tenantProperties = tenantProperties;
        this.objectMapper = objectMapper;
    }

    public CustomerWorkRuntimeConfig.RoutingPolicy requireActive(Long policyId,
                                                                  Long agentId,
                                                                  String channelCode) {
        AiModelRoutePolicy policy = requirePolicy(policyId);
        if (!ModelRoutePolicyStatus.ACTIVE.name().equals(policy.getStatus())
            || policy.getCurrentVersionId() == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "只能绑定 ACTIVE 模型路由策略");
        }
        AiModelRoutePolicyVersion version = versionMapper.selectOne(
            new QueryWrapper<AiModelRoutePolicyVersion>()
                .eq("id", policy.getCurrentVersionId())
                .eq("policy_id", policy.getId())
                .eq("tenant_id", policy.getTenantId())
                .eq("status", ModelRouteVersionStatus.ACTIVE.name()));
        if (version == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "路由策略当前版本不是 ACTIVE");
        }
        List<AiModelRouteRule> rules = ruleMapper.selectList(new QueryWrapper<AiModelRouteRule>()
            .eq("tenant_id", policy.getTenantId())
            .eq("policy_version_id", version.getId())
            .orderByAsc("priority", "id"));
        if (rules.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "路由策略当前版本没有规则");
        }

        Map<Long, AiModelConfig> deployments = new LinkedHashMap<>();
        for (Long deploymentId : rules.stream().map(AiModelRouteRule::getDeploymentId).distinct().toList()) {
            AiModelConfig deployment = modelConfigAccess.findVisibleById(deploymentId);
            if (deployment == null) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "路由策略引用的部署当前不可运行: " + deploymentId);
            }
            deployments.put(deploymentId, deployment);
        }

        CustomerWorkRuntimeConfig.RoutingPolicy runtime = new CustomerWorkRuntimeConfig.RoutingPolicy();
        runtime.setPolicyId(policy.getId());
        runtime.setVersionId(version.getId());
        runtime.setVersionNo(version.getVersionNo());
        runtime.setPolicyContentHash(version.getContentHash());
        runtime.setAgentId(agentId);
        runtime.setChannelCode(channelCode);
        runtime.setDeployments(deployments.values().stream()
            .sorted(Comparator.comparing(AiModelConfig::getId))
            .map(this::toDeployment)
            .toList());
        runtime.setRules(rules.stream().map(this::toRule).toList());
        return runtime;
    }

    /** 不读取凭据的当前版本身份，供调用谱系与审计归因使用。 */
    public ActivePolicyIdentity activeIdentity(Long policyId) {
        AiModelRoutePolicy policy = requirePolicy(policyId);
        if (!ModelRoutePolicyStatus.ACTIVE.name().equals(policy.getStatus())
            || policy.getCurrentVersionId() == null) {
            return null;
        }
        AiModelRoutePolicyVersion version = versionMapper.selectOne(
            new QueryWrapper<AiModelRoutePolicyVersion>()
                .eq("id", policy.getCurrentVersionId())
                .eq("policy_id", policy.getId())
                .eq("tenant_id", policy.getTenantId()));
        return version == null ? null : new ActivePolicyIdentity(policy.getId(), version.getId(),
            version.getVersionNo(), version.getContentHash());
    }

    private AiModelRoutePolicy requirePolicy(Long policyId) {
        if (policyId == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "modelRoutePolicyId 不能为空");
        }
        QueryWrapper<AiModelRoutePolicy> query = new QueryWrapper<AiModelRoutePolicy>().eq("id", policyId);
        if (tenantProperties.isEnabled()) {
            // 可靠发布 Worker 没有 Sa-Token 会话；运行时装配的租户真源只能是任务恢复的 TenantContext。
            String tenant = TenantContext.get();
            if (!StringUtils.hasText(tenant)) {
                throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文，无法访问路由策略");
            }
            query.eq("tenant_id", TenantContext.canonicalizeTenantId(tenant));
        }
        AiModelRoutePolicy policy = policyMapper.selectOne(query);
        if (policy == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型路由策略不存在: " + policyId);
        }
        return policy;
    }

    private CustomerWorkRuntimeConfig.RoutingDeployment toDeployment(AiModelConfig model) {
        CustomerWorkRuntimeConfig.RoutingDeployment deployment =
            new CustomerWorkRuntimeConfig.RoutingDeployment();
        deployment.setDeploymentId(model.getId());
        deployment.setProvider(model.getProvider());
        deployment.setName(model.getModel());
        deployment.setBaseUrl(model.getBaseUrl());
        deployment.setEndpointRevision(model.getEndpointRevision());
        deployment.setApiKeyCipher(secretRefService.resolveCipherText(
            model.getSecretRefId(), model.getTenantId(), model.getApiKey()));
        return deployment;
    }

    private CustomerWorkRuntimeConfig.RoutingRule toRule(AiModelRouteRule source) {
        CustomerWorkRuntimeConfig.RoutingRule rule = new CustomerWorkRuntimeConfig.RoutingRule();
        rule.setRuleId(source.getId());
        rule.setPurpose(source.getPurpose());
        rule.setDeploymentId(source.getDeploymentId());
        rule.setPriority(source.getPriority());
        rule.setCondition(toCondition(readCondition(source.getConditionJson())));
        return rule;
    }

    private CustomerWorkRuntimeConfig.RoutingCondition toCondition(ModelRouteCondition source) {
        CustomerWorkRuntimeConfig.RoutingCondition condition = new CustomerWorkRuntimeConfig.RoutingCondition();
        condition.setAgentIds(source.agentIds());
        condition.setChannelCodes(source.channelCodes());
        condition.setMinInputTokens(source.minInputTokens());
        condition.setMaxInputTokens(source.maxInputTokens());
        condition.setRequiresTools(source.requiresTools());
        condition.setRequiresStructuredOutput(source.requiresStructuredOutput());
        condition.setComplexity(source.complexity());
        return condition;
    }

    private ModelRouteCondition readCondition(String json) {
        try {
            ModelRouteCondition condition = objectMapper.readValue(json, ModelRouteCondition.class);
            return new ModelRouteCondition(
                condition.agentIds() == null ? List.of() : condition.agentIds(),
                condition.channelCodes() == null ? List.of() : condition.channelCodes(),
                condition.minInputTokens(), condition.maxInputTokens(), condition.requiresTools(),
                condition.requiresStructuredOutput(), condition.complexity());
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "路由条件数据不可解析");
        }
    }

    public record ActivePolicyIdentity(Long policyId,
                                       Long versionId,
                                       Integer versionNo,
                                       String contentHash) {
        public ActivePolicyIdentity {
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(versionId, "versionId");
        }
    }
}
