package com.richard.fyoung.customerwork.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 对话终止信封：同步、SSE、WebSocket 与 AG-UI 共用同一个结果模型。
 *
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "对话终止信封")
public record ChatTerminalEnvelope(
        @Schema(description = "已持久化的助手消息 ID") String messageId,
        @Schema(description = "Agent 终止原因", example = "MODEL_STOP") String finishReason,
        @Schema(description = "本轮模型调用累计用量") ChatUsageSnapshot usage,
        @Schema(description = "本轮调用 traceId") String traceId,
        @Schema(description = "本轮回答用到的知识引用；未启用受管知识库或未召回时为空列表")
        java.util.List<KnowledgeCitation> citations,
        @Schema(description = "本轮的任务清单；单步问题或模型未拆解时为空列表")
        java.util.List<TaskPlanItem> taskPlan) {
}
