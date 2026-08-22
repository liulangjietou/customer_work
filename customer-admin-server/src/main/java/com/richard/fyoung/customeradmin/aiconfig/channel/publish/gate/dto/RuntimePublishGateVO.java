package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateDecision;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateStatus;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;

import java.util.List;

/** 单个可靠发布任务的门禁事实。 */
public record RuntimePublishGateVO(
    String taskId,
    String publishStatus,
    EvalGateStatus gateStatus,
    String candidateContentHash,
    EvalVersionBinding candidateVersions,
    List<String> evalRunIds,
    EvalGateDecision decision,
    Long evaluatedAtMs,
    Long overrideId
) {
}
