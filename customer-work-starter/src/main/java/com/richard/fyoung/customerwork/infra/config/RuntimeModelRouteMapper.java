package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.model.routing.PolicyRouteSpec;

import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把跨服务 JSON 契约映射为不含凭据的路由领域规格。 */
public final class RuntimeModelRouteMapper {

    private RuntimeModelRouteMapper() {
    }

    public static PolicyRouteSpec toSpec(CustomerWorkRuntimeConfig.RoutingPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("routing policy is null");
        }
        List<PolicyRouteSpec.Rule> rules = policy.getRules() == null ? List.of()
            : policy.getRules().stream().map(RuntimeModelRouteMapper::toRule).toList();
        Map<Long, PolicyRouteSpec.Health> health = new LinkedHashMap<>();
        if (policy.getDeployments() != null) {
            for (CustomerWorkRuntimeConfig.RoutingDeployment deployment : policy.getDeployments()) {
                if (deployment != null && deployment.getDeploymentId() != null) {
                    health.put(deployment.getDeploymentId(), toHealth(deployment.getHealth()));
                }
            }
        }
        return new PolicyRouteSpec(policy.getPolicyId(), policy.getVersionId(), policy.getVersionNo(),
            policy.getPolicyContentHash(), policy.getAgentId(), policy.getChannelCode(), rules, health);
    }

    private static PolicyRouteSpec.Rule toRule(CustomerWorkRuntimeConfig.RoutingRule rule) {
        if (rule == null || rule.getPurpose() == null) {
            throw new IllegalArgumentException("routing rule or purpose is null");
        }
        CustomerWorkRuntimeConfig.RoutingCondition condition = rule.getCondition();
        PolicyRouteSpec.Condition mapped = condition == null ? PolicyRouteSpec.Condition.empty()
            : new PolicyRouteSpec.Condition(condition.getAgentIds(), condition.getChannelCodes(),
                condition.getMinInputTokens(), condition.getMaxInputTokens(), condition.getRequiresTools(),
                condition.getRequiresStructuredOutput(), condition.getComplexity());
        return new PolicyRouteSpec.Rule(rule.getRuleId(),
            PolicyRouteSpec.Purpose.valueOf(rule.getPurpose().trim().toUpperCase(Locale.ROOT)),
            rule.getDeploymentId(), rule.getPriority(), mapped);
    }

    private static PolicyRouteSpec.Health toHealth(CustomerWorkRuntimeConfig.HealthOverlay overlay) {
        if (overlay == null) {
            return new PolicyRouteSpec.Health("UNKNOWN", true, "AUTO", 0);
        }
        return new PolicyRouteSpec.Health(overlay.getEffectiveHealthStatus(),
            overlay.isRoutingAvailable(), overlay.getOverrideMode(), overlay.getRevision());
    }
}
