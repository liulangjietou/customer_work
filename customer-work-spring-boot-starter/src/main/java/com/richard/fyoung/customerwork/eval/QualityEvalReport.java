package com.richard.fyoung.customerwork.eval;

import lombok.Getter;

import java.util.List;

/**
 * 回复质量评测报告（LLM-as-Judge 汇总）。
 *
 * <p>核心指标：</p>
 * <ul>
 *   <li><b>avgScore</b>：所有用例的平均得分（1-5 分）；</li>
 *   <li><b>passRate</b>：得分 >= 3 的用例占比；</li>
 *   <li><b>categoryScores</b>：按类目的平均得分（便于发现短板类目）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class QualityEvalReport {

    private final int total;
    private final double avgScore;
    private final int passCount;
    private final List<String> failures;

    public QualityEvalReport(int total, double avgScore, int passCount, List<String> failures) {
        this.total = total;
        this.avgScore = avgScore;
        this.passCount = passCount;
        this.failures = failures;
    }

    public double passRate() {
        return total == 0 ? 0.0 : (double) passCount / total;
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Quality Eval Report (LLM-as-Judge) ===%n"));
        sb.append(String.format("total=%d, avgScore=%.2f, passRate=%.1f%%%n",
            total, avgScore, passRate() * 100));
        if (!failures.isEmpty()) {
            sb.append("failures:").append(System.lineSeparator());
            for (String f : failures) {
                sb.append("  - ").append(f).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }
}
