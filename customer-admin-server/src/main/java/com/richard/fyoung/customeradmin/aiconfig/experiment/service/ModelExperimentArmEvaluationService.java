package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentArm;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentArmEvalStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentArmEvalVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperimentArmEval;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentArmEvalMapper;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.JudgeModel;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalCase;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalReport;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalRunner;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 在线实验启动前的两臂离线质量评测。
 *
 * <p>被测模型只接收系统提示词和用例输入，工具列表恒为空，因此不会访问业务系统或产生工具副作用。
 * control 与 treatment 无论一臂是否失败都会分别执行并落事实，避免只留下半份门禁证据。</p>
 */
@Service
public class ModelExperimentArmEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ModelExperimentArmEvaluationService.class);
    static final double MIN_AVG_SCORE = 3.0D;
    static final double MIN_PASS_RATE = 0.8D;
    private static final int MODEL_TIMEOUT_SECONDS = 120;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String RESULT_PARSE_ERROR_CODE = "MODEL-EXPERIMENT-RESULT-PARSE-FAIL";

    private final AiModelExperimentArmEvalMapper mapper;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final ObjectMapper objectMapper;

    public ModelExperimentArmEvaluationService(AiModelExperimentArmEvalMapper mapper,
                                               AdminAgentInstanceFactory agentInstanceFactory,
                                               ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.agentInstanceFactory = agentInstanceFactory;
        this.objectMapper = objectMapper;
    }

    public GateResult evaluateBoth(AiModelExperiment experiment, AiAgent agent,
                                   EvalDatasetSnapshot snapshot) {
        QualityEvalCase[] parsed = readCases(snapshot);
        List<QualityEvalCase> cases = List.of(parsed);
        Model judgeModel = agentInstanceFactory.buildModelForDeployment(experiment.getJudgeDeploymentId());
        JudgeModel judge = judgeAdapter(judgeModel, experiment);
        ArmResult control = evaluateArm(experiment, agent, cases, judge, ModelExperimentArm.CONTROL,
            experiment.getControlDeploymentId(), experiment.getControlEndpointRevision());
        ArmResult treatment = evaluateArm(experiment, agent, cases, judge, ModelExperimentArm.TREATMENT,
            experiment.getTreatmentDeploymentId(), experiment.getTreatmentEndpointRevision());
        return new GateResult(control, treatment);
    }

    public List<ModelExperimentArmEvalVO> list(Long experimentId) {
        return mapper.selectList(new LambdaQueryWrapper<AiModelExperimentArmEval>()
                .eq(AiModelExperimentArmEval::getExperimentId, experimentId)
                .orderByDesc(AiModelExperimentArmEval::getAttemptNo)
                .orderByAsc(AiModelExperimentArmEval::getArm))
            .stream().map(this::toVO).toList();
    }

    private ArmResult evaluateArm(AiModelExperiment experiment, AiAgent agent,
                                  List<QualityEvalCase> cases, JudgeModel judge,
                                  ModelExperimentArm arm, Long deploymentId,
                                  Integer endpointRevision) {
        AiModelExperimentArmEval row = startRow(experiment, arm, deploymentId, endpointRevision);
        try {
            Model candidate = agentInstanceFactory.buildModelForDeployment(deploymentId);
            List<String> replies = new ArrayList<>(cases.size());
            for (QualityEvalCase evalCase : cases) {
                replies.add(generateReply(candidate,
                    AdminAgentInstanceFactory.effectiveSystemPrompt(agent), evalCase.input()));
            }
            QualityEvalReport report = new QualityEvalRunner(judge).run(cases, replies);
            boolean passed = report.getStatus().name().equals("COMPLETED")
                && report.getAvgScore() >= MIN_AVG_SCORE
                && report.passRate() >= MIN_PASS_RATE;
            finishRow(row, passed ? ModelExperimentArmEvalStatus.PASSED
                    : ModelExperimentArmEvalStatus.FAILED,
                report, null);
            return new ArmResult(arm, passed, row.getId(), row.getStatus(), null);
        } catch (Exception e) {
            String message = errorMessage(e);
            finishRow(row, ModelExperimentArmEvalStatus.ERROR, null, message);
            return new ArmResult(arm, false, row.getId(), row.getStatus(), message);
        }
    }

    private AiModelExperimentArmEval startRow(AiModelExperiment experiment, ModelExperimentArm arm,
                                              Long deploymentId, Integer endpointRevision) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<AiModelExperimentArmEval>()
            .eq(AiModelExperimentArmEval::getExperimentId, experiment.getId())
            .eq(AiModelExperimentArmEval::getArm, arm.name()));
        AiModelExperimentArmEval row = new AiModelExperimentArmEval();
        row.setTenantId(experiment.getTenantId());
        row.setExperimentId(experiment.getId());
        row.setArm(arm.name());
        row.setAttemptNo((count == null ? 0 : count.intValue()) + 1);
        row.setDeploymentId(deploymentId);
        row.setEndpointRevision(endpointRevision);
        row.setDatasetReleaseId(experiment.getDatasetReleaseId());
        row.setDatasetSnapshotVersionId(experiment.getDatasetSnapshotVersionId());
        row.setDatasetContentHash(experiment.getDatasetContentHash());
        row.setJudgeDeploymentId(experiment.getJudgeDeploymentId());
        row.setJudgeEndpointRevision(experiment.getJudgeEndpointRevision());
        row.setRubricVersion(QualityEvalRunner.rubricVersion());
        row.setStatus(ModelExperimentArmEvalStatus.RUNNING.name());
        row.setStartedAt(LocalDateTime.now());
        row.setCreateTime(row.getStartedAt());
        mapper.insert(row);
        return row;
    }

    private void finishRow(AiModelExperimentArmEval row, ModelExperimentArmEvalStatus status,
                           QualityEvalReport report, String errorMessage) {
        row.setStatus(status.name());
        row.setCompletedAt(LocalDateTime.now());
        if (report != null) {
            row.setTotal(report.getTotal());
            row.setJudged(report.getJudgedCount());
            row.setPassed(report.getPassCount());
            row.setAvgScore(decimal(report.getAvgScore()));
            row.setPassRate(decimal(report.passRate()));
            row.setFailedCaseIdsJson(writeJson(report.getFailedCaseIds()));
            row.setErrorCaseIdsJson(writeJson(report.getErrorCaseIds()));
        }
        row.setErrorMessage(truncate(errorMessage));
        mapper.updateById(row);
    }

    private QualityEvalCase[] readCases(EvalDatasetSnapshot snapshot) {
        try {
            return objectMapper.readValue(snapshot.casesJson(), QualityEvalCase[].class);
        } catch (Exception e) {
            throw new IllegalStateException("quality dataset snapshot is not readable: " + snapshot.versionId(), e);
        }
    }

    private JudgeModel judgeAdapter(Model model, AiModelExperiment experiment) {
        return new JudgeModel() {
            @Override
            public Msg chat(Msg message) {
                String text = invoke(model, List.of(message));
                return Msg.builder().role(MsgRole.ASSISTANT).name("judge")
                    .content(TextBlock.builder().text(text).build()).build();
            }

            @Override
            public String version() {
                return EvalFingerprint.of("experiment-judge-v1", experiment.getJudgeDeploymentId(),
                    experiment.getJudgeModelRef(), experiment.getJudgeEndpointRevision());
            }
        };
    }

    private String generateReply(Model model, String systemPrompt, String input) {
        Msg system = Msg.builder().role(MsgRole.SYSTEM).name("system")
            .content(TextBlock.builder().text(systemPrompt).build()).build();
        Msg user = Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(input).build()).build();
        return invoke(model, List.of(system, user));
    }

    private String invoke(Model model, List<Msg> messages) {
        List<ChatResponse> responses = model.stream(messages, List.of(), GenerateOptions.builder().build())
            .collectList().block(Duration.ofSeconds(MODEL_TIMEOUT_SECONDS));
        if (CollectionUtils.isEmpty(responses)) {
            throw new IllegalStateException("model returned empty response");
        }
        String text = responses.stream()
            .flatMap(response -> response.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("model returned empty text");
        }
        return text.trim();
    }

    private ModelExperimentArmEvalVO toVO(AiModelExperimentArmEval row) {
        return new ModelExperimentArmEvalVO(row.getId(), row.getArm(), row.getAttemptNo(),
            row.getDeploymentId(), row.getEndpointRevision(), row.getDatasetReleaseId(),
            row.getDatasetSnapshotVersionId(), row.getDatasetContentHash(), row.getJudgeDeploymentId(),
            row.getJudgeEndpointRevision(), row.getRubricVersion(), row.getStatus(), row.getTotal(),
            row.getJudged(), row.getPassed(), row.getAvgScore(), row.getPassRate(),
            readStringList(row.getFailedCaseIdsJson(), row.getId(), "failedCaseIds"),
            readStringList(row.getErrorCaseIdsJson(), row.getId(), "errorCaseIds"),
            row.getErrorMessage(), row.getStartedAt(), row.getCompletedAt());
    }

    private List<String> readStringList(String json, Long evaluationId, String field) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.error("experiment arm evaluation result parse failed, code={}, evaluationId={}, field={}",
                RESULT_PARSE_ERROR_CODE, evaluationId, field, e);
            return List.of();
        }
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize experiment eval result", e);
        }
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value) || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    public record ArmResult(ModelExperimentArm arm, boolean passed, Long factId,
                            String status, String errorMessage) {
    }

    public record GateResult(ArmResult control, ArmResult treatment) {
        public boolean passed() {
            return control.passed() && treatment.passed();
        }

        public String failureSummary() {
            return "control=" + control.status() + ", treatment=" + treatment.status();
        }
    }
}
