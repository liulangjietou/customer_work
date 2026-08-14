package com.richard.fyoung.customerwork.capability.eval;

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

    /**
     * 失败用例 ID 列表。
     *
     * <p>{@link #failures} 是给人看的格式化明细，机器要从里面反解 ID 既脆弱又依赖格式；
     * 版本间比对"哪些用例从通过变成了失败"（回归）必须有稳定的 ID 集合，故单独一份。</p>
     */
    private final List<String> failedCaseIds;

    public EvalReport(int total, int correct, int fastLaneHits, int explicitCases,
                      List<String> failures, List<String> failedCaseIds) {
        this.total = total;
        this.correct = correct;
        this.fastLaneHits = fastLaneHits;
        this.explicitCases = explicitCases;
        this.failures = failures;
        this.failedCaseIds = failedCaseIds == null ? List.of() : List.copyOf(failedCaseIds);
    }

    /** 兼容重载：不带失败 ID（无法参与回归识别，仅适合一次性查看）。 */
    public EvalReport(int total, int correct, int fastLaneHits, int explicitCases, List<String> failures) {
        this(total, correct, fastLaneHits, explicitCases, failures, List.of());
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
