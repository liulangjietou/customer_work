package com.richard.fyoung.customerwork.core.model.routing;

import java.util.List;

/** 不可变模型路由规格；不包含模型实例或任何凭据。 */
public record PolicyRouteSpec(Long policyId,
                              Long versionId,
                              Integer versionNo,
                              String contentHash,
                              Long agentId,
                              String channelCode,
                              List<Rule> rules) {

    public PolicyRouteSpec {
        rules = rules == null ? List.of() : List.copyOf(rules);
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
}
