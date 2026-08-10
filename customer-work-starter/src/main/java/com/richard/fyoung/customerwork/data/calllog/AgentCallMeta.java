package com.richard.fyoung.customerwork.data.calllog;

/**
 * 一次智能体调用的调用方元数据（不可变），由调用方放入 {@link io.agentscope.core.agent.RuntimeContext}
 * （{@code ctx.put(AgentCallMeta.class, meta)}），供 {@code AgentCallTimingMiddleware} 采集时读取。
 *
 * <p>全部字段可缺失：中间件读不到对应字段时按 {@code RuntimeContext} 的 userId/sessionId 及 MDC 的
 * requestId 降级，不报错（防御式兜底收敛在中间件）。{@code sessionType} 缺省视为
 * {@link AgentCallSessionType#CHAT}。</p>
 *
 * @param requestId   请求 ID（缺失时中间件回退 MDC 的 requestId）
 * @param username    用户名（缺失时中间件回退 ctx.getUserId()）
 * @param agentCode   智能体编码（业务标识，缺失时中间件回退 agent 名称）
 * @param agentName   智能体名称（缺失时中间件回退 agent 名称）
 * @param sessionType 会话类型（缺失视为 CHAT）
 * @param question    用户问题（缺失时中间件回退 onAgent 入参首条用户消息文本）
 * @author owlzhangfq@gmail.com
 */
public record AgentCallMeta(String requestId, String username, String agentCode, String agentName,
                            AgentCallSessionType sessionType, String question) {
}
