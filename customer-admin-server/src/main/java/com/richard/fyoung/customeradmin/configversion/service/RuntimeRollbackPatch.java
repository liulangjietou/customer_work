package com.richard.fyoung.customeradmin.configversion.service;

/**
 * 历史运行时快照允许回退的最小行为补丁。
 *
 * <p>模型、凭据、MCP、路由与在线实验不属于补丁；它们必须在目标租户下从当前权威数据重组。</p>
 */
public record RuntimeRollbackPatch(String systemPrompt, Integer maxIters) {
}
