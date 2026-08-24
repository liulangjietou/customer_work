package com.richard.fyoung.customerwork.core.dto;

import io.agentscope.core.model.ChatUsage;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一轮对话的 token 用量快照。
 *
 * <p>领域层不直接暴露 AgentScope 的 {@link ChatUsage}，避免接入协议与框架类型耦合。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "对话 token 用量")
public record ChatUsageSnapshot(
        @Schema(description = "输入 token 数") int inputTokens,
        @Schema(description = "输出 token 数") int outputTokens,
        @Schema(description = "缓存命中的输入 token 数") int cachedTokens,
        @Schema(description = "输入与输出 token 总数") int totalTokens,
        @Schema(description = "模型调用累计耗时（秒）") double timeSeconds) {

    public static ChatUsageSnapshot empty() {
        return new ChatUsageSnapshot(0, 0, 0, 0, 0D);
    }

    public static ChatUsageSnapshot from(ChatUsage usage) {
        if (usage == null) {
            return empty();
        }
        return new ChatUsageSnapshot(usage.getInputTokens(), usage.getOutputTokens(),
            usage.getCachedTokens(), usage.getTotalTokens(), usage.getTime());
    }

    public ChatUsageSnapshot plus(ChatUsageSnapshot other) {
        if (other == null) {
            return this;
        }
        return new ChatUsageSnapshot(
            inputTokens + other.inputTokens,
            outputTokens + other.outputTokens,
            cachedTokens + other.cachedTokens,
            totalTokens + other.totalTokens,
            timeSeconds + other.timeSeconds);
    }
}
