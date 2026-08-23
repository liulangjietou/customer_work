package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.util.List;

/**
 * 类型化路由条件。空值表示该维度不限制；所有非空维度按 AND 匹配，列表内部按 OR 匹配。
 */
public record ModelRouteCondition(List<Long> agentIds,
                                  List<String> channelCodes,
                                  Integer minInputTokens,
                                  Integer maxInputTokens,
                                  Boolean requiresTools,
                                  Boolean requiresStructuredOutput,
                                  String complexity) {

    public static ModelRouteCondition empty() {
        return new ModelRouteCondition(List.of(), List.of(), null, null, null, null, null);
    }
}
