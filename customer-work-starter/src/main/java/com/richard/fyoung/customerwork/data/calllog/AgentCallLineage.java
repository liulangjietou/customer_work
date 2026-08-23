package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;

/**
 * 一次调用在开始时冻结的运行谱系。
 *
 * <p>{@code traceId} 关联 OTel/Tempo，{@code runtimeRevision/runtimeContentHash} 关联配置发布与实例 ACK，
 * {@code versionBinding} 复用评测域的模型、提示词、Agent、知识库与工具版本口径。数据集/Judge/rubric
 * 属于离线评测上下文，在线调用中保持空值。</p>
 */
public record AgentCallLineage(
    String traceId,
    String runtimeRevision,
    String runtimeContentHash,
    EvalVersionBinding versionBinding
) {

    public AgentCallLineage {
        traceId = normalize(traceId);
        runtimeRevision = normalize(runtimeRevision);
        runtimeContentHash = normalize(runtimeContentHash);
        versionBinding = versionBinding == null ? EvalVersionBinding.legacy("") : versionBinding;
    }

    public static AgentCallLineage empty() {
        return new AgentCallLineage("", "", "", EvalVersionBinding.legacy(""));
    }

    /** traceId 来自当前请求 Reactor Context，覆盖提供器里可能过期的空值。 */
    public AgentCallLineage withTraceId(String currentTraceId) {
        return new AgentCallLineage(currentTraceId, runtimeRevision, runtimeContentHash, versionBinding);
    }

    /** 是否没有任何可用于归因的发布或制品版本；traceId 不参与该判断。 */
    public boolean hasArtifactContext() {
        return !runtimeRevision.isEmpty()
            || !runtimeContentHash.isEmpty()
            || !versionBinding.modelVersion().isEmpty()
            || !versionBinding.promptVersion().isEmpty()
            || !versionBinding.agentVersion().isEmpty()
            || !versionBinding.knowledgeBaseVersion().isEmpty()
            || !versionBinding.toolVersion().isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
