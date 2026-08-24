package com.richard.fyoung.customeradmin.workspace.callstats.dto;

/** MOCK/DRY_RUN 执行结果；externalCallCount 永远为 0，项目不存在 LIVE 模式。 */
public record AgentReplayExecutionVO(
    String replayId,
    Long callLogId,
    String mode,
    boolean isolated,
    int externalCallCount,
    int mockedModelCalls,
    int mockedRagRetrievals,
    int mockedToolCalls,
    String replayedAnswer,
    AgentReplayDiffVO diff,
    long executedAtMs
) {
}
