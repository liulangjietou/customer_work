package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AgentReplayProperties;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallReplayManifestVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentReplayArtifactDiffVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentReplayDiffVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentReplayExecuteRequest;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentReplayExecutionVO;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.data.calllog.AgentCallLineage;
import com.richard.fyoung.customerwork.data.calllog.AgentReplaySnapshot;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 安全重放编排。MOCK/DRY_RUN 都以已落库快照驱动并把模型、RAG、工具全部替换成测试桩，
 * 因此外部调用数恒为零；DRY_RUN 额外要求独立权限和服务端隔离部署标识。
 */
@Service
public class AgentReplayExecutionService {

    private final AgentCallStatsService statsService;
    private final AgentCallMetaFactory callMetaFactory;
    private final AgentReplayProperties properties;

    public AgentReplayExecutionService(AgentCallStatsService statsService,
                                       AgentCallMetaFactory callMetaFactory,
                                       AgentReplayProperties properties) {
        this.statsService = statsService;
        this.callMetaFactory = callMetaFactory;
        this.properties = properties;
    }

    public AgentReplayExecutionVO execute(long id, String source, AgentReplayExecuteRequest request) {
        ReplayMode mode = ReplayMode.parse(request == null ? null : request.mode());
        if (mode == ReplayMode.DRY_RUN && !properties.allowsDryRun()) {
            throw new BizException(ResultCode.FORBIDDEN, properties.dryRunBlockedReason());
        }
        AgentCallReplayManifestVO manifest = statsService.replayManifest(id, source);
        if (mode == ReplayMode.DRY_RUN && !manifest.captureWarnings().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "DRY_RUN 所需历史事实不完整: " + String.join("；", manifest.captureWarnings()));
        }

        String replayedAnswer = request != null && request.mockAnswer() != null
            ? request.mockAnswer() : manifest.recordedAnswer();
        EvalVersionBinding currentBinding = currentBinding(manifest);
        List<AgentReplayArtifactDiffVO> artifactDiffs = artifactDiffs(
            manifest.versionBinding(), currentBinding);
        List<String> warnings = new ArrayList<>(manifest.captureWarnings());
        artifactDiffs.stream().filter(diff -> "CHANGED".equals(diff.status()))
            .forEach(diff -> warnings.add(diff.artifact() + " 已漂移"));
        AgentReplayDiffVO diff = new AgentReplayDiffVO(
            !Objects.equals(manifest.recordedAnswer(), replayedAnswer),
            sha256(manifest.recordedAnswer()),
            sha256(replayedAnswer),
            commonPrefix(manifest.recordedAnswer(), replayedAnswer),
            artifactDiffs,
            warnings);
        AgentReplaySnapshot snapshot = manifest.replaySnapshot() == null
            ? AgentReplaySnapshot.empty() : manifest.replaySnapshot();
        return new AgentReplayExecutionVO(UUID.randomUUID().toString(), manifest.callLogId(), mode.name(),
            mode == ReplayMode.DRY_RUN, 0, snapshot.modelCalls().size(),
            snapshot.ragRetrievals().size(), snapshot.toolCalls().size(), replayedAnswer, diff,
            System.currentTimeMillis());
    }

    private EvalVersionBinding currentBinding(AgentCallReplayManifestVO manifest) {
        if (!"ADMIN".equals(manifest.source())) {
            return EvalVersionBinding.legacy("");
        }
        AgentCallLineage lineage = callMetaFactory.currentLineage(manifest.agentCode());
        return lineage == null ? EvalVersionBinding.legacy("") : lineage.versionBinding();
    }

    private List<AgentReplayArtifactDiffVO> artifactDiffs(EvalVersionBinding recorded,
                                                          EvalVersionBinding current) {
        EvalVersionBinding left = recorded == null ? EvalVersionBinding.legacy("") : recorded;
        EvalVersionBinding right = current == null ? EvalVersionBinding.legacy("") : current;
        return List.of(
            artifact("MODEL", left, right, EvalVersionBinding::modelVersion),
            artifact("PROMPT", left, right, EvalVersionBinding::promptVersion),
            artifact("AGENT", left, right, EvalVersionBinding::agentVersion),
            artifact("KNOWLEDGE_BASE", left, right, EvalVersionBinding::knowledgeBaseVersion),
            artifact("TOOL", left, right, EvalVersionBinding::toolVersion));
    }

    private AgentReplayArtifactDiffVO artifact(String name, EvalVersionBinding recorded,
                                                EvalVersionBinding current,
                                                Function<EvalVersionBinding, String> getter) {
        String before = getter.apply(recorded);
        String now = getter.apply(current);
        String status;
        if (!StringUtils.hasText(before) || !StringUtils.hasText(now)) {
            status = "UNAVAILABLE";
        } else {
            status = Objects.equals(before, now) ? "SAME" : "CHANGED";
        }
        return new AgentReplayArtifactDiffVO(name, before, now, status);
    }

    private int commonPrefix(String first, String second) {
        String left = first == null ? "" : first;
        String right = second == null ? "" : second;
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private enum ReplayMode {
        MOCK,
        DRY_RUN;

        private static ReplayMode parse(String raw) {
            if (!StringUtils.hasText(raw)) {
                return MOCK;
            }
            try {
                return ReplayMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "重放模式只允许 MOCK 或 DRY_RUN，不存在 LIVE 模式");
            }
        }
    }
}
