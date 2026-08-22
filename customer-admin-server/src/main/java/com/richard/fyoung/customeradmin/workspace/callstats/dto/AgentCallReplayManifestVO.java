package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;

import java.util.List;

/**
 * 安全重放清单：冻结原始输入、输出、分段与版本谱系，但不自动再次执行模型或工具。
 *
 * <p>生产工具可能发消息、改数据或触发审批，后台查看权限不能隐式升级为执行权限。因此本接口只提供
 * 可审计、可导出的复现输入；真正重跑必须进入隔离环境并逐项授权。</p>
 */
public record AgentCallReplayManifestVO(
    int schemaVersion,
    String mode,
    boolean executable,
    String executionBlockedReason,
    String source,
    Long callLogId,
    String traceId,
    String requestId,
    String agentCode,
    String sessionType,
    String question,
    String recordedAnswer,
    String startTime,
    String runtimeRevision,
    String runtimeContentHash,
    Long experimentId,
    Integer experimentRevision,
    String experimentArm,
    Long experimentDeploymentId,
    Integer experimentBucket,
    EvalVersionBinding versionBinding,
    List<AgentCallSegmentVO> segments
) {
}
