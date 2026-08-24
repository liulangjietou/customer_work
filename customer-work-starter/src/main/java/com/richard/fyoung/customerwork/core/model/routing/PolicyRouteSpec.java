package com.richard.fyoung.customerwork.core.model.routing;

import java.util.List;
import java.util.Map;

/** 不可变模型路由规格；不包含模型实例或任何凭据。 */
public record PolicyRouteSpec(Long policyId,
                              Long versionId,
                              Integer versionNo,
                              String contentHash,
                              Long agentId,
                              String channelCode,
                              List<Rule> rules,
                              Map<Long, Health> healthOverlays) {

    public PolicyRouteSpec {
        rules = rules == null ? List.of() : List.copyOf(rules);
        healthOverlays = healthOverlays == null ? Map.of() : Map.copyOf(healthOverlays);
    }

    public PolicyRouteSpec(Long policyId, Long versionId, Integer versionNo,
                           String contentHash, Long agentId, String channelCode,
                           List<Rule> rules) {
        this(policyId, versionId, versionNo, contentHash, agentId, channelCode, rules, Map.of());
    }

    public record Rule(Long ruleId,
                       Purpose purpose,
                       Long deploymentId,
                       Integer priority,
                       Condition condition) {
    }

    public record Condition(List<Long> agentIds,
                            List<String> channelCodes,
                            Integer minInputTokens,
                            Integer maxInputTokens,
                            Boolean requiresTools,
                            Boolean requiresStructuredOutput,
                            String complexity) {
        public Condition {
            agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
            channelCodes = channelCodes == null ? List.of() : List.copyOf(channelCodes);
        }

        public static Condition empty() {
            return new Condition(List.of(), List.of(), null, null, null, null, null);
        }
    }

    public enum Purpose {
        DEFAULT,
        ECONOMY,
        COMPLEX_REASONING,
        FALLBACK
    }

    /** 控制面发布时冻结的动态健康投影；缺项按兼容旧载荷可用处理。 */
    public record Health(String effectiveStatus,
                         boolean routingAvailable,
                         String overrideMode,
                         Integer revision) {
    }
}
