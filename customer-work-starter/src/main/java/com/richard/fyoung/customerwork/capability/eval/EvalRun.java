package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次评测运行的不可变事实快照。
 *
 * <p>与 {@link EvalReport}/{@link QualityEvalReport} 的区别：那两个是<b>某一类评测</b>的即时算分结果，
 * 字段各不相同、活在内存里；本 record 是"这次跑了什么、跑出什么"的<b>可持久化事实</b>，
 * 把两类报告归一到同一组可比字段上，这样"这版比上版好还是坏"才有统一口径。</p>
 *
 * <p><b>为什么要归一化 {@code primaryMetric}</b>：意图评测的主指标是准确率（0-1），质量评测是平均分（1-5）。
 * 不归一就没法用同一段对比逻辑，每加一类评测都要再写一遍比较代码。故约定主指标一律折算到 0-1，
 * 原始值完整保留在 {@link #metrics} 里不丢精度。</p>
 *
 * @param runId          运行 ID（UUID，跨库引用用）
 * @param evalType       评测类型
 * @param total          用例总数
 * @param passed         通过数
 * @param primaryMetric  主指标，归一化到 0-1（INTENT=准确率；QUALITY=平均分/5）
 * @param secondaryMetric 次指标，归一化到 0-1（INTENT=快车道覆盖率；QUALITY=通过率）
 * @param failedCaseIds  失败用例 ID 列表——版本间做<b>回归识别</b>的依据（上版过、这版挂的那些）
 * @param failures       失败明细（人读，含输入与实际/期望值）
 * @param metrics        该类型的完整原始指标，避免归一化丢信息
 * @param trigger        触发来源
 * @param datasetSize    评测集规模（用例数变了指标就不可直接比，对比时要提示）
 * @param versionBinding 本次运行的数据集、模型、提示词、Agent、知识库、工具、Judge、rubric 版本
 * @param remark         备注（人工填，如"换 qwen-max 后重跑"）
 * @param createdAtMs    运行时间戳（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record EvalRun(
    String runId,
    EvalType evalType,
    int total,
    int passed,
    double primaryMetric,
    double secondaryMetric,
    List<String> failedCaseIds,
    List<String> failures,
    Map<String, Object> metrics,
    EvalTrigger trigger,
    int datasetSize,
    EvalVersionBinding versionBinding,
    String remark,
    long createdAtMs
) {

    /** Judge 打分满分，用于把 1-5 分折算成 0-1 的主指标。 */
    private static final double QUALITY_MAX_SCORE = 5.0d;

    public EvalRun {
        failedCaseIds = failedCaseIds == null ? List.of() : List.copyOf(failedCaseIds);
        failures = failures == null ? List.of() : List.copyOf(failures);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        versionBinding = versionBinding == null ? EvalVersionBinding.legacy("") : versionBinding;
    }

    /** 由意图评测报告构造：主指标取准确率，次指标取快车道覆盖率。 */
    public static EvalRun fromIntent(EvalReport report, EvalTrigger trigger,
                                     EvalVersionBinding versionBinding, String remark) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("accuracy", report.accuracy());
        metrics.put("fastLaneCoverage", report.fastLaneCoverage());
        metrics.put("correct", report.getCorrect());
        metrics.put("fastLaneHits", report.getFastLaneHits());
        metrics.put("explicitCases", report.getExplicitCases());
        return new EvalRun(
            UUID.randomUUID().toString(),
            EvalType.INTENT,
            report.getTotal(),
            report.getCorrect(),
            report.accuracy(),
            report.fastLaneCoverage(),
            report.getFailedCaseIds(),
            report.getFailures(),
            metrics,
            trigger,
            report.getTotal(),
            versionBinding,
            remark,
            System.currentTimeMillis());
    }

    /** 兼容旧调用方。 */
    public static EvalRun fromIntent(EvalReport report, EvalTrigger trigger,
                                     String promptFingerprint, String remark) {
        return fromIntent(report, trigger, EvalVersionBinding.legacy(promptFingerprint), remark);
    }

    /** 由质量评测报告构造：主指标取平均分折算值，次指标取通过率。 */
    public static EvalRun fromQuality(QualityEvalReport report, EvalTrigger trigger,
                                      EvalVersionBinding versionBinding, String remark) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("avgScore", report.getAvgScore());
        metrics.put("passRate", report.passRate());
        metrics.put("passCount", report.getPassCount());
        metrics.put("judgedCount", report.getJudgedCount());
        metrics.put("errorCount", report.getErrorCount());
        metrics.put("status", report.getStatus().name());
        metrics.put("judgeErrors", report.getErrors());
        List<String> failedIds = new ArrayList<>(report.getFailedCaseIds());
        failedIds.addAll(report.getErrorCaseIds());
        List<String> failureDetails = new ArrayList<>(report.getFailures());
        failureDetails.addAll(report.getErrors());
        return new EvalRun(
            UUID.randomUUID().toString(),
            EvalType.QUALITY,
            report.getTotal(),
            report.getPassCount(),
            report.getAvgScore() / QUALITY_MAX_SCORE,
            report.passRate(),
            List.copyOf(failedIds),
            List.copyOf(failureDetails),
            metrics,
            trigger,
            report.getTotal(),
            versionBinding,
            remark,
            System.currentTimeMillis());
    }

    /** 兼容旧调用方。 */
    public static EvalRun fromQuality(QualityEvalReport report, EvalTrigger trigger,
                                      String promptFingerprint, String remark) {
        return fromQuality(report, trigger, EvalVersionBinding.legacy(promptFingerprint), remark);
    }

    /** 兼容旧调用方：历史运行只有提示词指纹，其余版本维度明确留空。 */
    public EvalRun(String runId, EvalType evalType, int total, int passed,
                   double primaryMetric, double secondaryMetric,
                   List<String> failedCaseIds, List<String> failures,
                   Map<String, Object> metrics, EvalTrigger trigger, int datasetSize,
                   String promptFingerprint, String remark, long createdAtMs) {
        this(runId, evalType, total, passed, primaryMetric, secondaryMetric,
            failedCaseIds, failures, metrics, trigger, datasetSize,
            EvalVersionBinding.legacy(promptFingerprint), remark, createdAtMs);
    }

    /** 保留原 API/JSON 字段，实际单一真相在 {@link #versionBinding}。 */
    @JsonProperty("promptFingerprint")
    public String promptFingerprint() {
        return versionBinding == null ? "" : versionBinding.promptVersion();
    }

    /** 质量评测的 Judge 异常会把整轮标成 ERROR；意图评测始终是完整运行。 */
    @JsonProperty("status")
    public QualityEvalStatus status() {
        Object value = metrics == null ? null : metrics.get("status");
        return "ERROR".equals(String.valueOf(value)) ? QualityEvalStatus.ERROR : QualityEvalStatus.COMPLETED;
    }

    /** 发布门禁只接受完整运行，不能把 Judge 宕机当成一轮通过。 */
    @JsonProperty("gatePassed")
    public boolean gatePassed() {
        return status() == QualityEvalStatus.COMPLETED;
    }
}
