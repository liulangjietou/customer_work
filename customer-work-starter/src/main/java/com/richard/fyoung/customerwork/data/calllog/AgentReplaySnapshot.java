package com.richard.fyoung.customerwork.data.calllog;

import java.util.List;

/**
 * 一次调用可安全持久化的重放事实。原始消息、工具结果和知识正文不落库，只保存非密钥参数、
 * 结构摘要、引用标识、长度与 SHA-256，既能判断漂移，也不会复制一份高敏业务数据。
 */
public record AgentReplaySnapshot(
    int schemaVersion,
    List<ModelCallSnapshot> modelCalls,
    List<RagRetrievalSnapshot> ragRetrievals,
    List<ToolCallSnapshot> toolCalls
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AgentReplaySnapshot {
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
        ragRetrievals = ragRetrievals == null ? List.of() : List.copyOf(ragRetrievals);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AgentReplaySnapshot empty() {
        return new AgentReplaySnapshot(CURRENT_SCHEMA_VERSION, List.of(), List.of(), List.of());
    }

    public boolean hasCapturedFacts() {
        return !modelCalls.isEmpty() || !ragRetrievals.isEmpty() || !toolCalls.isEmpty();
    }

    /** 最终模型输入只留哈希；参数均为真正影响生成行为且不含凭据的白名单字段。 */
    public record ModelCallSnapshot(
        int sequence,
        String modelName,
        GenerateOptionsSnapshot parameters,
        int messageCount,
        int toolSchemaCount,
        List<String> toolNames,
        String inputSha256
    ) {
        public ModelCallSnapshot {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }
    }

    public record GenerateOptionsSnapshot(
        Boolean stream,
        Double temperature,
        Double topP,
        Integer topK,
        Integer maxTokens,
        Integer maxCompletionTokens,
        Double frequencyPenalty,
        Double presencePenalty,
        Integer thinkingBudget,
        String reasoningEffort,
        Long seed,
        Boolean cacheControl,
        Boolean parallelToolCalls,
        String toolChoice,
        String responseFormat,
        Long timeoutMs,
        Integer maxAttempts,
        Long initialBackoffMs,
        Long maxBackoffMs,
        Double backoffMultiplier,
        List<String> additionalHeaderNames,
        List<String> additionalBodyParamNames,
        String additionalBodySha256,
        List<String> additionalQueryParamNames
    ) {
        public GenerateOptionsSnapshot {
            additionalHeaderNames = safeSorted(additionalHeaderNames);
            additionalBodyParamNames = safeSorted(additionalBodyParamNames);
            additionalQueryParamNames = safeSorted(additionalQueryParamNames);
        }

        private static List<String> safeSorted(List<String> values) {
            return values == null ? List.of() : values.stream().sorted().toList();
        }
    }

    /** RAG 正文不复制，只保留命中状态、正文摘要和可定位的引用元数据。 */
    public record RagRetrievalSnapshot(
        int sequence,
        String agentCode,
        String status,
        String querySha256,
        int resultChars,
        String resultSha256,
        List<RagReferenceSnapshot> references
    ) {
        public RagRetrievalSnapshot {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    public record RagReferenceSnapshot(String knowledgeBase, String documentId,
                                       String chunkId, String score) {
    }

    /** 工具参数只保存字段结构与脱敏哈希，结果只保存状态、长度和哈希。 */
    public record ToolCallSnapshot(
        int sequence,
        String toolCallId,
        String toolName,
        String kind,
        String inputShape,
        String inputSha256,
        String resultState,
        int resultChars,
        String resultSha256,
        String errorMessage
    ) {
    }
}
