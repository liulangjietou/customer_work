package com.richard.fyoung.customerwork.core.model.routing;

/**
 * 单次模型调用可观测到的路由事实。调用方只填写权威已知值，其余维度由模型装饰器从消息、工具和
 * GenerateOptions 推导；null 表示未知而不是 false。
 */
public record ModelRouteHint(Long agentId,
                             String channelCode,
                             Integer inputTokens,
                             Boolean requiresTools,
                             Boolean requiresStructuredOutput,
                             String complexity) {

    public static ModelRouteHint empty() {
        return new ModelRouteHint(null, null, null, null, null, null);
    }
}
