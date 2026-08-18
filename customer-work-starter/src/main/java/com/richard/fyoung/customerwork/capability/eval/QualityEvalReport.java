package com.richard.fyoung.customerwork.capability.eval;

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
    private final int judgedCount;
    private final List<String> errors;

    /**
     * 失败用例 ID 列表（得分低于通过阈值的用例）。
     *
     * <p>与 {@link EvalReport#getFailedCaseIds()} 同一用途：给版本间回归识别一份稳定的 ID 集合，
     * 不必从人读明细里反解格式。</p>
     */
    private final List<String> failedCaseIds;
    private final List<String> errorCaseIds;

    public QualityEvalReport(int total, double avgScore, int passCount,
                             List<String> failures, List<String> failedCaseIds,
                             int judgedCount, List<String> errors, List<String> errorCaseIds) {
        this.total = total;
        this.avgScore = avgScore;
        this.passCount = passCount;
        this.failures = failures == null ? List.of() : List.copyOf(failures);
        this.failedCaseIds = failedCaseIds == null ? List.of() : List.copyOf(failedCaseIds);
        this.judgedCount = judgedCount;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.errorCaseIds = errorCaseIds == null ? List.of() : List.copyOf(errorCaseIds);
    }

    public QualityEvalReport(int total, double avgScore, int passCount,
                             List<String> failures, List<String> failedCaseIds) {
        this.total = total;
        this.avgScore = avgScore;
        this.passCount = passCount;
        this.failures = failures == null ? List.of() : List.copyOf(failures);
        this.failedCaseIds = failedCaseIds == null ? List.of() : List.copyOf(failedCaseIds);
        this.judgedCount = total;
        this.errors = List.of();
        this.errorCaseIds = List.of();
    }

    /** 兼容重载：不带失败 ID（无法参与回归识别，仅适合一次性查看）。 */
    public QualityEvalReport(int total, double avgScore, int passCount, List<String> failures) {
        this(total, avgScore, passCount, failures, List.of());
    }

    public double passRate() {
        return total == 0 ? 0.0 : (double) passCount / total;
    }

    public int getErrorCount() {
        return errors.size();
    }

    public QualityEvalStatus getStatus() {
        return errors.isEmpty() ? QualityEvalStatus.COMPLETED : QualityEvalStatus.ERROR;
    }

    public boolean isGatePassed() {
        return getStatus() == QualityEvalStatus.COMPLETED;
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Quality Eval Report (LLM-as-Judge) ===%n"));
        sb.append(String.format("status=%s, total=%d, judged=%d, errors=%d, avgScore=%.2f, passRate=%.1f%%%n",
            getStatus(), total, judgedCount, getErrorCount(), avgScore, passRate() * 100));
        if (!failures.isEmpty()) {
            sb.append("failures:").append(System.lineSeparator());
            for (String f : failures) {
                sb.append("  - ").append(f).append(System.lineSeparator());
            }
        }
        if (!errors.isEmpty()) {
            sb.append("judge errors:").append(System.lineSeparator());
            for (String error : errors) {
                sb.append("  - ").append(error).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }
}
