package com.richard.fyoung.customerwork.calllog;

/**
 * 智能体调用来源会话类型（区分普通问答与 VibeCoding 场景，供报表分组）。
 *
 * <p>8080 客服链路默认 {@link #CHAT}；VibeCoding 类调用方在构造 {@code AgentCallMeta} 时显式置为
 * {@link #VIBE_CODING}。缺失时中间件降级为 {@link #CHAT}。</p>
 * @author owlzhangfq@gmail.com
 */
public enum AgentCallSessionType {
    CHAT, VIBE_CODING
}
