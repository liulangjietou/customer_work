package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 两次评测运行的对比结果——"这版比上版好还是坏"的答案。
 *
 * <p>光看总分是不够的：准确率从 90% 涨到 92% 可能同时意味着"修好了 3 个、弄坏了 1 个"。
 * 那个被弄坏的用例（{@link #regressions}）才是要人立刻去看的东西，它会被总分的上涨掩盖。
 * 故本类同时给出两个视角：总分方向（{@link #verdict()}）与逐用例的进出（回归 / 修复）。</p>
 *
 * <p><b>诚实边界</b>：回归与修复由两次运行的<b>失败用例 ID 集合</b>做差集算出，
 * 没有保存"全部用例 ID"。因此评测集本身增删用例时（{@link #datasetChanged()} 为真），
 * 新增用例若失败会被计入回归——这时指标本就不可直接比，请以 {@code datasetChanged} 为准先做判断。</p>
 *
 * @param current     本次运行
 * @param baseline    基线运行（上一次同类型运行）；首次运行时为 {@code null}
 * @param regressions 回归用例：上次通过、这次失败
 * @param fixes       修复用例：上次失败、这次通过
 * @author owlzhangfq@gmail.com
 */
// 派生属性（verdict/delta 等）只下发不回读：反序列化时 record 构造器仅认 4 个组件，
// 忽略未知字段后这些值由方法就地重算，两端口径天然一致
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalComparison(
    EvalRun current,
    EvalRun baseline,
    List<String> regressions,
    List<String> fixes
) {

    /**
     * 主指标浮点比较容差。
     *
     * <p>0.001 相当于 1000 个用例里差 1 个，小于此幅度视为持平——
     * 不设容差会让每次浮点尾差都被报成"有变化"，噪声淹没真信号。</p>
     */
    private static final double EPSILON = 0.001d;

    /** 结论。 */
    public enum Verdict {
        /** 首次运行，无基线可比。 */
        FIRST_RUN,
        /** 主指标上升。 */
        IMPROVED,
        /** 主指标下降。 */
        REGRESSED,
        /** 主指标持平（容差内）。 */
        UNCHANGED
    }

    /** 按两次运行算出回归与修复。{@code baseline} 为空表示首次运行。 */
    public static EvalComparison of(EvalRun current, EvalRun baseline) {
        if (baseline == null) {
            return new EvalComparison(current, null, List.of(), List.of());
        }
        return new EvalComparison(current, baseline,
            difference(current.failedCaseIds(), baseline.failedCaseIds()),
            difference(baseline.failedCaseIds(), current.failedCaseIds()));
    }

    /**
     * 主指标变化量（本次 - 基线）；首次运行返回 0。
     *
     * <p>派生值标 {@code @JsonProperty} 一并下发：调用方（后台页面 / CI）要的是结论，
     * 让每个消费端各自重算一遍 delta 与 verdict，迟早算出不一致的口径。</p>
     */
    @JsonProperty("primaryDelta")
    public double primaryDelta() {
        return baseline == null ? 0.0d : current.primaryMetric() - baseline.primaryMetric();
    }

    /** 次指标变化量（本次 - 基线）；首次运行返回 0。 */
    @JsonProperty("secondaryDelta")
    public double secondaryDelta() {
        return baseline == null ? 0.0d : current.secondaryMetric() - baseline.secondaryMetric();
    }

    /** 评测集规模是否变了——变了则两次指标不可直接比较。 */
    @JsonProperty("datasetChanged")
    public boolean datasetChanged() {
        return baseline != null && baseline.datasetSize() != current.datasetSize();
    }

    /**
     * 两次运行之间提示词是否改过——<b>效果归因的支点</b>。
     *
     * <p>把"指标掉了"拆成两问：改过吗？改过，那就去比这两版提示词的全文；没改过，
     * 那就别再对着提示词逐字看了，该去查模型、知识库或评测集本身。
     * 没有这一位，每次指标波动都要把所有可能性重查一遍。</p>
     *
     * <p>指纹为空（未启用追踪或取不到提示词）时返回 false：无从判断，不做无根据的断言。</p>
     */
    @JsonProperty("promptChanged")
    public boolean promptChanged() {
        if (baseline == null) {
            return false;
        }
        String currentPrompt = current.promptFingerprint();
        String baselinePrompt = baseline.promptFingerprint();
        if (currentPrompt == null || currentPrompt.isEmpty()
            || baselinePrompt == null || baselinePrompt.isEmpty()) {
            return false;
        }
        return !currentPrompt.equals(baselinePrompt);
    }

    /** 总分方向的结论。逐用例的进出另见 {@link #regressions()}，两者要一起看。 */
    @JsonProperty("verdict")
    public Verdict verdict() {
        if (baseline == null) {
            return Verdict.FIRST_RUN;
        }
        double delta = primaryDelta();
        if (delta > EPSILON) {
            return Verdict.IMPROVED;
        }
        if (delta < -EPSILON) {
            return Verdict.REGRESSED;
        }
        return Verdict.UNCHANGED;
    }

    /** 文本摘要（CI 输出 / 日志用）；不进 JSON 响应，结构化字段已足够前端渲染。 */
    @JsonIgnore
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Eval Comparison (%s) ===%n", current.evalType()));
        sb.append(String.format("verdict=%s, primary=%.4f (%+.4f), secondary=%.4f (%+.4f)%n",
            verdict(), current.primaryMetric(), primaryDelta(),
            current.secondaryMetric(), secondaryDelta()));
        if (datasetChanged()) {
            sb.append(String.format("WARNING: dataset size changed %d -> %d, metrics not directly comparable%n",
                baseline.datasetSize(), current.datasetSize()));
        }
        if (promptChanged()) {
            sb.append(String.format("prompt changed: %s -> %s (likely the cause of any metric shift)%n",
                baseline.promptFingerprint(), current.promptFingerprint()));
        }
        if (!regressions.isEmpty()) {
            sb.append("regressions (passed before, failed now): ").append(regressions)
                .append(System.lineSeparator());
        }
        if (!fixes.isEmpty()) {
            sb.append("fixes (failed before, passed now): ").append(fixes)
                .append(System.lineSeparator());
        }
        return sb.toString();
    }

    /** 差集：在 {@code source} 里但不在 {@code exclude} 里，保持原有顺序。 */
    private static List<String> difference(List<String> source, List<String> exclude) {
        Set<String> excluded = new LinkedHashSet<>(exclude);
        List<String> result = new ArrayList<>();
        for (String id : source) {
            if (!excluded.contains(id)) {
                result.add(id);
            }
        }
        return List.copyOf(result);
    }
}
