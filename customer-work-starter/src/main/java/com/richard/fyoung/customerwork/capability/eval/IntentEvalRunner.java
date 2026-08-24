package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 意图路由评测执行器（借鉴 AliGo 测评系统）。
 *
 * <p>用 {@link MultiAgentOrchestrator#fastRouteIntent} 对评测集逐用例打分，量化<b>规则快车道</b>的
 * 准确率与覆盖率——离线确定性、可在 CI 跑、可版本对比。真实回复质量（相关性）评测需接 LLM-as-judge
 * （需真实模型 Key），不在本离线评测范围。</p>
 *
 * <p>评测集 = classpath 种子（{@code eval/intent-eval-cases.json}，随代码走、经 code review）
 * + {@link EvalCaseStore} 里的增量（badcase 回流与人工补充）。合并规则见 {@link EvalDatasetMerger}。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class IntentEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(IntentEvalRunner.class);

    private static final String EVALUATOR_VERSION = "intent-fast-route-v1";

    private final MultiAgentOrchestrator orchestrator;
    private final EvalDatasetCatalog datasetCatalog;

    @Autowired
    public IntentEvalRunner(MultiAgentOrchestrator orchestrator, EvalCaseStore caseStore) {
        this.orchestrator = orchestrator;
        this.datasetCatalog = new EvalDatasetCatalog(caseStore);
    }

    /** 便捷重载：只用 classpath 种子（单测与离线试跑用）。 */
    public IntentEvalRunner(MultiAgentOrchestrator orchestrator) {
        this(orchestrator, new InMemoryEvalCaseStore());
    }

    /** 加载评测集：classpath 种子 + 库中增量，同 ID 以库为准，停用的剔除。 */
    public List<EvalCase> loadDataset() {
        return datasetCatalog.intentCases();
    }

    /** 跑默认评测集。 */
    public EvalReport run() {
        return run(loadDataset());
    }

    /** 对给定评测集逐用例打分，汇总报告。 */
    public EvalReport run(List<EvalCase> cases) {
        int correct = 0;
        int fastLaneHits = 0;
        int explicitCases = 0;
        List<String> failures = new ArrayList<>();
        List<String> failedCaseIds = new ArrayList<>();

        for (EvalCase c : cases) {
            Optional<String> actual = orchestrator.fastRouteIntent(c.input());
            boolean fastHit = actual.isPresent();
            if (fastHit) {
                fastLaneHits++;
            }
            boolean isExplicit = c.expectedIntent() != null;
            if (isExplicit) {
                explicitCases++;
            }
            boolean ok = isExplicit
                ? actual.map(a -> a.equals(c.expectedIntent())).orElse(false)
                : !fastHit;   // 模糊用例：快车道应不命中，正确地交给 LLM
            if (ok) {
                correct++;
            } else {
                failures.add(String.format("%s: input='%s' expected=%s actual=%s",
                    c.id(), c.input(), c.expectedIntent(), actual.orElse("(none)")));
                failedCaseIds.add(c.id());
            }
        }
        EvalReport report = new EvalReport(cases.size(), correct, fastLaneHits, explicitCases,
            failures, failedCaseIds);
        log.info("intent eval done: total={}, accuracy={}, coverage={}",
            report.getTotal(), String.format("%.1f%%", report.accuracy() * 100),
            String.format("%.1f%%", report.fastLaneCoverage() * 100));
        return report;
    }

    /** 意图判定 rubric/算法版本，随判分规则变更而变化。 */
    public static String evaluatorVersion() {
        return EvalFingerprint.of(EVALUATOR_VERSION);
    }
}
