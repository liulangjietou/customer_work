package com.richard.fyoung.customeradmin.ops;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.calllog.AgentCallTimingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.PromptInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.IndirectInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.capability.eval.KnowledgeCandidateTrialRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.ops.domain.FrozenKnowledgeModel;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateEvaluation;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeTrialSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshotter;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalDatasetSnapshotStore;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalCase;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalRunner;
import java.util.List;

/** 服务编排和 SQL 往返共用真实指纹输入，避免测试用任意字符串伪装完整快照。 */
public final class KnowledgeCandidateTestInputs {
    public static final String ID = "478b7f10-46ba-4217-947f-d86528aa8fbe";
    public static final String HASH = "a".repeat(64);
    public static final List<QualityEvalCase> CASES = List.of(
        new QualityEvalCase("target", "纸质票如何开具", "开票规则", "knowledge"),
        new QualityEvalCase("existing", "纸质票时限", "原有时限", "knowledge"));

    private KnowledgeCandidateTestInputs() { }

    /** 服务编排测试显式关闭基础治理，治理行为由运行器的独立用例覆盖。 */
    public static KnowledgeCandidateTrialRunner trialRunner() {
        var properties = new CustomerWorkProperties();
        properties.getCallLog().setEnabled(false);
        return new KnowledgeCandidateTrialRunner(
            new AgentCallTimingMiddleware(properties, null, null, null), null,
            new MaskingMiddleware(false, null),
            new PromptInjectionGuardMiddleware(false, "拒绝", List.of(), null),
            new IndirectInjectionGuardMiddleware(false, false, List.of(), null));
    }

    public static KnowledgeCandidateBinding binding(String tenant) {
        var snapshot = new EvalDatasetSnapshotter(new InMemoryEvalDatasetSnapshotStore()).snapshot(EvalType.QUALITY, CASES);
        var knowledge = new KnowledgeTrialSnapshot(tenant, ID, 2, 3, "content-hash", "baseline-fingerprint",
            "[]", "[{\"id\":24,\"content\":\"候选开票规则\"}]", 24);
        return new KnowledgeCandidateBinding(1, 7, "assistant", "冻结提示词", 3, knowledge,
            new FrozenKnowledgeModel(11L, 2, "openai", "https://model.example/v1", "main-model", 8192),
            new FrozenKnowledgeModel(12L, 3, "openai", "https://judge.example/v1", "judge-model", 8192),
            "release-1", snapshot, "target", KnowledgeCandidateBinding.CURRENT_TOOL_VERSION, QualityEvalRunner.rubricVersion());
    }

    public static KnowledgeCandidateBinding change(KnowledgeCandidateBinding binding, String field, Object value) {
        var mapper = new ObjectMapper();
        var tree = mapper.valueToTree(binding);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set(field, mapper.valueToTree(value));
        return mapper.convertValue(tree, KnowledgeCandidateBinding.class);
    }

    public static KnowledgeCandidateEvaluation evaluation(KnowledgeCandidateBinding binding) {
        var judge = new QualityEvalRunner(message -> io.agentscope.core.message.Msg.builder()
            .role(io.agentscope.core.message.MsgRole.ASSISTANT)
            .content(io.agentscope.core.message.TextBlock.builder().text("SCORE: 5").build()).build());
        var replies = List.of("开票规则", "原有时限");
        var baseline = EvalRun.fromQuality(judge.run(CASES, replies), EvalTrigger.MANUAL, binding.baselineVersions(), null);
        var current = EvalRun.fromQuality(judge.run(CASES, replies), EvalTrigger.MANUAL, binding.versions(), null);
        return new KnowledgeCandidateEvaluation(binding.knowledge().tenantId(), binding.improvementId(), binding.fingerprint(),
            baseline, current, replies, replies, List.of("target", "existing"));
    }
}
