package com.richard.fyoung.customerwork.eval;

import lombok.Getter;

import java.util.List;

/**
 * 意图评测报告（借鉴 AliGo 测评系统的"可视化报告/版本对比"）。
 *
 * <p>核心指标：</p>
 * <ul>
 *   <li><b>accuracy</b>：判定正确数 / 总用例数（明确用例命中正确意图、模糊用例正确地交给 LLM）；</li>
 *   <li><b>fastLaneCoverage</b>：快车道命中数 / 明确意图用例数（规则能直接处理的占比）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class EvalReport {

    private final int total;
    private final int correct;
    private final int fastLaneHits;
    private final int explicitCases;
    private final List<String> failures;

    public EvalReport(int total, int correct, int fastLaneHits, int explicitCases, List<String> failures) {
        this.total = total;
        this.correct = correct;
        this.fastLaneHits = fastLaneHits;
        this.explicitCases = explicitCases;
        this.failures = failures;
    }

    public double accuracy() {
        return total == 0 ? 0.0 : (double) correct / total;
    }

    public double fastLaneCoverage() {
        return explicitCases == 0 ? 0.0 : (double) fastLaneHits / explicitCases;
    }

    /** 文本报告（轻量"可视化"，便于 CI 输出与版本对比）。 */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Intent Eval Report ===%n"));
        sb.append(String.format("total=%d, correct=%d, accuracy=%.1f%%%n", total, correct, accuracy() * 100));
        sb.append(String.format("explicit=%d, fastLaneHits=%d, coverage=%.1f%%%n",
            explicitCases, fastLaneHits, fastLaneCoverage() * 100));
        if (!failures.isEmpty()) {
            sb.append("failures:").append(System.lineSeparator());
            for (String f : failures) {
                sb.append("  - ").append(f).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }
}
