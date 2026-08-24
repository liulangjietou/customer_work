package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AgentReplayProperties;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallReplayManifestVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentReplayExecuteRequest;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentReplayExecutionVO;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.data.calllog.AgentCallLineage;
import com.richard.fyoung.customerwork.data.calllog.AgentReplaySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentReplayExecutionServiceTest {

    private AgentCallStatsService statsService;
    private AgentCallMetaFactory metaFactory;
    private AgentReplayProperties properties;
    private AgentReplayExecutionService service;

    @BeforeEach
    void setUp() {
        statsService = mock(AgentCallStatsService.class);
        metaFactory = mock(AgentCallMetaFactory.class);
        properties = new AgentReplayProperties();
        service = new AgentReplayExecutionService(statsService, metaFactory, properties);
    }

    @Test
    void mock_shouldNeverCallExternalSystems_andReturnArtifactAndAnswerDiff() {
        when(statsService.replayManifest(7L, "ADMIN")).thenReturn(manifest(List.of()));
        when(metaFactory.currentLineage("agent-a")).thenReturn(new AgentCallLineage("", "", "",
            binding("model-current", "prompt-old", "agent-old", "kb-old", "tool-old")));

        AgentReplayExecutionVO result = service.execute(7L, "ADMIN",
            new AgentReplayExecuteRequest("MOCK", "new answer"));

        assertEquals(0, result.externalCallCount());
        assertEquals(1, result.mockedModelCalls());
        assertTrue(result.diff().answerChanged());
        assertTrue(result.diff().artifactVersions().stream()
            .anyMatch(item -> "MODEL".equals(item.artifact()) && "CHANGED".equals(item.status())));
    }

    @Test
    void dryRun_shouldFailClosedUnlessServerIsExplicitlyIsolated() {
        BizException denied = assertThrows(BizException.class, () -> service.execute(7L, "ADMIN",
            new AgentReplayExecuteRequest("DRY_RUN", null)));
        assertTrue(denied.getMessage().contains("DRY_RUN"));
    }

    @Test
    void dryRun_shouldRejectIncompleteHistoricalCapture_evenInIsolatedDeployment() {
        properties.setDryRunEnabled(true);
        properties.setEnvironment("isolated");
        when(statsService.replayManifest(7L, "ADMIN"))
            .thenReturn(manifest(List.of("历史记录缺少模型参数快照")));

        assertThrows(BizException.class, () -> service.execute(7L, "ADMIN",
            new AgentReplayExecuteRequest("DRY_RUN", null)));
    }

    @Test
    void unknownMode_shouldRejectLiveExecution() {
        BizException denied = assertThrows(BizException.class, () -> service.execute(7L, "ADMIN",
            new AgentReplayExecuteRequest("LIVE", null)));
        assertFalse(denied.getMessage().isBlank());
    }

    private AgentCallReplayManifestVO manifest(List<String> warnings) {
        AgentReplaySnapshot.ModelCallSnapshot modelCall = new AgentReplaySnapshot.ModelCallSnapshot(
            1, "model", null, 1, 0, List.of(), "input-hash");
        AgentReplaySnapshot snapshot = new AgentReplaySnapshot(1, List.of(modelCall), List.of(), List.of());
        return new AgentCallReplayManifestVO(3, "MOCK_DEFAULT", true, "isolated only", "ADMIN",
            7L, "trace", "request", "agent-a", "CHAT", "question", "old answer", "time",
            "revision", "hash", null, null, null, null, null,
            binding("model-old", "prompt-old", "agent-old", "kb-old", "tool-old"),
            List.of(), snapshot, List.of("MOCK", "DRY_RUN"), warnings);
    }

    private EvalVersionBinding binding(String model, String prompt, String agent, String kb, String tool) {
        return new EvalVersionBinding("", "", model, prompt, agent, kb, tool, "", "");
    }
}
