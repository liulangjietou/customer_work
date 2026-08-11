package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
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
 * <p>评测集从 classpath 的 {@code eval/intent-eval-cases.json} 加载（用例沉淀复用）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class IntentEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(IntentEvalRunner.class);

    private static final String DATASET_PATH = "eval/intent-eval-cases.json";
    private static final String ERR_LOAD = "EVAL-LOAD-FAIL";

    private final MultiAgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentEvalRunner(MultiAgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** 从 classpath 加载评测集。 */
    public List<EvalCase> loadDataset() {
        try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
            return List.of(objectMapper.readValue(in, EvalCase[].class));
        } catch (Exception e) {
            log.error("load eval dataset failed, errorCode={}, path={}", ERR_LOAD, DATASET_PATH, e);
            throw new IllegalStateException("eval dataset not loadable: " + DATASET_PATH, e);
        }
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
            }
        }
        EvalReport report = new EvalReport(cases.size(), correct, fastLaneHits, explicitCases, failures);
        log.info("intent eval done: total={}, accuracy={}, coverage={}",
            report.getTotal(), String.format("%.1f%%", report.accuracy() * 100),
            String.format("%.1f%%", report.fastLaneCoverage() * 100));
        return report;
    }
}
