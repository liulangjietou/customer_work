package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperimentArmEval;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentArmEvalMapper;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExperimentArmEvaluationServiceTest {

    @Test
    void shouldEvaluateAndPersistBothArmsIndependently() {
        AiModelExperimentArmEvalMapper mapper = mock(AiModelExperimentArmEvalMapper.class);
        AdminAgentInstanceFactory factory = mock(AdminAgentInstanceFactory.class);
        Model model = mock(Model.class);
        when(factory.buildModelForDeployment(any())).thenReturn(model);
        ChatResponse response = ChatResponse.builder()
            .content(List.of(TextBlock.builder().text("SCORE: 5\n理由：符合预期").build()))
            .build();
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(response));
        when(mapper.selectCount(any())).thenReturn(0L);
        AtomicLong ids = new AtomicLong(1);
        doAnswer(invocation -> assignId(invocation, ids)).when(mapper)
            .insert(any(AiModelExperimentArmEval.class));

        ModelExperimentArmEvaluationService service =
            new ModelExperimentArmEvaluationService(mapper, factory, new ObjectMapper());
        AiModelExperiment experiment = experiment();
        AiAgent agent = new AiAgent();
        agent.setSystemPrompt("你是客服");
        EvalDatasetSnapshot snapshot = new EvalDatasetSnapshot("snapshot-1",
            com.richard.fyoung.customerwork.capability.eval.EvalType.QUALITY, "hash-1", 1,
            "[{\"id\":\"q1\",\"input\":\"hello\",\"expected\":\"helpful\",\"category\":\"test\"}]",
            1L);

        ModelExperimentArmEvaluationService.GateResult result =
            service.evaluateBoth(experiment, agent, snapshot);

        assertTrue(result.passed());
        assertEquals("PASSED", result.control().status());
        assertEquals("PASSED", result.treatment().status());
        verify(mapper, times(2)).insert(any(AiModelExperimentArmEval.class));
        verify(mapper, times(2)).updateById(any(AiModelExperimentArmEval.class));
    }

    private Integer assignId(InvocationOnMock invocation, AtomicLong ids) {
        AiModelExperimentArmEval row = invocation.getArgument(0);
        row.setId(ids.getAndIncrement());
        return 1;
    }

    private AiModelExperiment experiment() {
        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setId(10L);
        experiment.setTenantId("default");
        experiment.setControlDeploymentId(1L);
        experiment.setControlEndpointRevision(1);
        experiment.setTreatmentDeploymentId(2L);
        experiment.setTreatmentEndpointRevision(1);
        experiment.setJudgeDeploymentId(3L);
        experiment.setJudgeModelRef("judge");
        experiment.setJudgeEndpointRevision(1);
        experiment.setDatasetReleaseId("release-1");
        experiment.setDatasetSnapshotVersionId("snapshot-1");
        experiment.setDatasetContentHash("hash-1");
        return experiment;
    }
}
