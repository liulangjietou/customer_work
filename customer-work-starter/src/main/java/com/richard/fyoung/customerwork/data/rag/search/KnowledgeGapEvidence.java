package com.richard.fyoung.customerwork.data.rag.search;

import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import io.agentscope.core.agent.RuntimeContext;

/** 最近一次未命中的检索证据；不包含用户标识或会话正文，不推测缺失的入口及意图。 */
public record KnowledgeGapEvidence(Path path, String agentCode, String channelCode,
                                    String sessionType, RetrievalResult retrievalResult) {
    public enum Path { TOOL, INJECTION }
    public enum RetrievalResult { EMPTY }

    /** 从框架上下文捕获；智能体编码回退值只能来自服务端构建期。 */
    public static KnowledgeGapEvidence capture(RuntimeContext context, Path path, String executingAgent) {
        AgentInvocationIdentity identity = context == null ? null : context.get(AgentInvocationIdentity.class);
        AgentCallMeta meta = context == null ? null : context.get(AgentCallMeta.class);
        String agent = executingAgent != null ? executingAgent
            : identity != null ? identity.agentCode() : null;
        return new KnowledgeGapEvidence(path, agent, identity == null ? null : identity.channelCode(),
            meta == null || meta.sessionType() == null ? null : meta.sessionType().name(),
            RetrievalResult.EMPTY);
    }
}
