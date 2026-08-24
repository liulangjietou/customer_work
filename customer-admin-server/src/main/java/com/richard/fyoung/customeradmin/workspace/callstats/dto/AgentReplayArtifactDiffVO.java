package com.richard.fyoung.customeradmin.workspace.callstats.dto;

/** 原调用制品版本与当前可见版本的逐维差异。 */
public record AgentReplayArtifactDiffVO(
    String artifact,
    String recordedVersion,
    String currentVersion,
    String status
) {
}
