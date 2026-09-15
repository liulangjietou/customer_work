package com.richard.fyoung.customeradmin.aiconfig.agent.publication;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateDecision;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import java.util.List;

/** 正式配置的发布事实；草稿试用结果不参与门禁或实例确认判定。 */
public record AgentPublicationCheck(long agentId, String agentName, Long runtimeRevision,
    boolean agentEnabled, boolean publishingEnabled, List<Channel> channels,
    CandidateStatus candidateStatus, EvalVersionBinding currentCandidate,
    Publication latestPublication, boolean currentRuntimeConfirmed, long checkedAtMs) {

    public enum CandidateStatus { READY, PUBLISHING_DISABLED, AGENT_DISABLED, CHANNEL_MISSING, UNAVAILABLE }
    public enum CandidateMatch { MATCH, CHANGED, NOT_PREPARED, UNAVAILABLE }
    public enum Confirmation { CONFIRMED, PARTIAL, WAITING, REJECTED, NO_TARGETS, LEGACY_UNVERIFIED }

    public record Channel(String code, boolean enabled) { }

    /** null 的冻结目标表示历史任务未保存目标清单，不能据此证明全部目标已确认。 */
    public record Publication(String taskId, String intent, String status, String revision,
        String candidateContentHash, EvalVersionBinding candidateVersions, CandidateMatch currentMatch,
        String gateStatus, EvalGateDecision gateDecision, List<String> evalRunIds, Long evaluatedAtMs,
        Long overrideId, List<String> targetInstances, List<Acknowledgement> acknowledgements,
        Confirmation confirmation, Long updatedAtMs) { }

    public record Acknowledgement(String instanceId, String status, Long appliedAtMs) { }
}
