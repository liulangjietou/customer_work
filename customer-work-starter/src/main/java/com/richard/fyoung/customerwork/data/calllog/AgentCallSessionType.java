package com.richard.fyoung.customerwork.data.calllog;

/**
 * 智能体调用来源会话类型，离线评测不得归为线上对话或实验曝光。
 *
 * <p>8080 客服链路默认 {@link #CHAT}；VibeCoding 类调用方在构造 {@code AgentCallMeta} 时显式置为
 * {@link #VIBE_CODING}。缺失时中间件降级为 {@link #CHAT}。</p>
 * @author owlzhangfq@gmail.com
 */
public enum AgentCallSessionType {
    CHAT, VIBE_CODING, EVALUATION
}
