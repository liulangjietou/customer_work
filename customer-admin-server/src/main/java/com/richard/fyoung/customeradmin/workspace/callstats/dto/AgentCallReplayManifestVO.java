package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.data.calllog.AgentReplaySnapshot;

import java.util.List;

/**
 * 安全重放清单：冻结原始输入、输出、分段、版本谱系与脱敏执行快照。
 *
 * <p>生产工具可能发消息、改数据或触发审批，后台查看权限不能隐式升级为执行权限。因此本接口只提供
 * 可审计、可导出的复现输入；默认只运行 MOCK，DRY_RUN 还必须经过独立权限与隔离环境策略。</p>
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
    List<AgentCallSegmentVO> segments,
    AgentReplaySnapshot replaySnapshot,
    List<String> supportedModes,
    List<String> captureWarnings
) {

    /** 兼容 schema v2 的既有单测与调用方。 */
    public AgentCallReplayManifestVO(
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
        List<AgentCallSegmentVO> segments) {
        this(schemaVersion, mode, executable, executionBlockedReason, source, callLogId, traceId,
            requestId, agentCode, sessionType, question, recordedAnswer, startTime, runtimeRevision,
            runtimeContentHash, experimentId, experimentRevision, experimentArm,
            experimentDeploymentId, experimentBucket, versionBinding, segments,
            AgentReplaySnapshot.empty(), List.of("MOCK"), List.of());
    }
}
