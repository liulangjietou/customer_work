package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import java.util.List;

/** 重放输出与原调用的结构化差异。 */
public record AgentReplayDiffVO(
    boolean answerChanged,
    String recordedAnswerSha256,
    String replayedAnswerSha256,
    int commonPrefixChars,
    List<AgentReplayArtifactDiffVO> artifactVersions,
    List<String> warnings
) {
    public AgentReplayDiffVO {
        artifactVersions = artifactVersions == null ? List.of() : List.copyOf(artifactVersions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
