package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.capability.prompt.PromptVersionTracker;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 评测编排服务——把"跑一遍标准集 → 出报告 → 对比上一版"串成一次调用。
 *
 * <p>此前 {@link IntentEvalRunner}/{@link QualityEvalRunner} 只能算出一份内存里的报告，没有调用方、
 * 没有落库、没有纵向对比：等于体温计造好了放在抽屉里。本类补上缺的那一段——每次运行落一条
 * {@link EvalRun}，并自动与上一次同类型运行比出<b>指标变化</b>和<b>回归用例</b>，
 * 让改提示词 / 换模型这类动作有据可依，而不是靠人肉体感。</p>
 *
 * <p><b>两类评测的代价不同</b>：意图评测纯离线（不调模型，可进 CI 门禁、可定时跑）；
 * 质量评测要逐条调 Agent 生成回复再调 Judge 打分，一次运行有实打实的 token 成本，
 * 故只在显式触发时跑，且缺少 {@link JudgeModel} 装配时直接 fail fast 说明原因，
 * 而不是返回一份全是中性分的假报告。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    /** 评测会话 ID 前缀：与真实用户会话区分开，便于排查与清理。 */
    private static final String EVAL_SESSION_PREFIX = "eval-";

    private final IntentEvalRunner intentRunner;
    private final EvalRunStore store;
    private final EvalCaseStore caseStore;
    private final ObjectProvider<JudgeModel> judgeModelProvider;
    private final ObjectProvider<CustomerServiceService> chatServiceProvider;
    private final ObjectProvider<PromptVersionTracker> promptTrackerProvider;

    public EvalService(IntentEvalRunner intentRunner,
                       EvalRunStore store,
                       EvalCaseStore caseStore,
                       ObjectProvider<JudgeModel> judgeModelProvider,
                       ObjectProvider<CustomerServiceService> chatServiceProvider,
                       ObjectProvider<PromptVersionTracker> promptTrackerProvider) {
        this.intentRunner = intentRunner;
        this.store = store;
        this.caseStore = caseStore;
        this.judgeModelProvider = judgeModelProvider;
        this.chatServiceProvider = chatServiceProvider;
        this.promptTrackerProvider = promptTrackerProvider;
    }

    /**
     * 跑意图评测标准集，落库并与上一版对比。
     *
     * <p>离线确定性：不调模型、无外部依赖，适合定时跑与 CI 门禁。</p>
     */
    public EvalComparison runIntent(EvalTrigger trigger, String remark) {
        EvalReport report = intentRunner.run();
        return persistAndCompare(EvalRun.fromIntent(report, trigger, capturePrompt(), remark));
    }

    /**
     * 跑质量评测标准集（LLM-as-Judge），落库并与上一版对比。
     *
     * <p>每个用例用<b>独立会话</b>生成回复：共用一个会话会让前一条用例的对话历史进入后一条的上下文，
     * 评测结果随用例顺序变化而不可复现。</p>
     *
     * @throws IllegalStateException 未装配 {@link JudgeModel} 或主链路服务不可用时
     */
    public EvalComparison runQuality(EvalTrigger trigger, String remark) {
        JudgeModel judgeModel = judgeModelProvider.getIfAvailable();
        if (judgeModel == null) {
            throw new IllegalStateException(
                "quality eval unavailable: no JudgeModel bean configured (needs a real model key)");
        }
        // Runner 无状态、只持有协作者，按次构造即可，不必占一个 Bean 位
        QualityEvalRunner runner = new QualityEvalRunner(judgeModel, caseStore);
        CustomerServiceService chatService = chatServiceProvider.getIfAvailable();
        if (chatService == null) {
            throw new IllegalStateException(
                "quality eval unavailable: CustomerServiceService not present in this context");
        }
        List<QualityEvalCase> cases = runner.loadDataset();
        QualityEvalReport report = runner.run(cases, generateReplies(chatService, cases));
        return persistAndCompare(EvalRun.fromQuality(report, trigger, capturePrompt(), remark));
    }

    /**
     * 记下本次运行时生效的提示词指纹。
     *
     * <p>这一位是效果归因的支点：下次指标掉了，先比指纹——变了就去看那两版提示词的差异，
     * 没变就别再对着提示词逐字找原因。未装配追踪器时返回空串，对比时会跳过该维度而非乱下结论。</p>
     */
    private String capturePrompt() {
        PromptVersionTracker tracker = promptTrackerProvider.getIfAvailable();
        return tracker == null ? "" : tracker.captureCurrent();
    }

    /** 某类型最近若干次运行（时间倒序）。 */
    public List<EvalRun> recent(EvalType type, int limit) {
        return store.findRecent(type, limit);
    }

    /** 按运行 ID 查一次运行。 */
    public Optional<EvalRun> find(String runId) {
        return store.find(runId);
    }

    /**
     * 取某次运行与它上一版的对比（历史回看用）。
     *
     * @return 运行不存在时返回空
     */
    public Optional<EvalComparison> compareWithBaseline(String runId) {
        return store.find(runId).map(run ->
            EvalComparison.of(run, store.findBaseline(run.evalType(), run.runId()).orElse(null)));
    }

    /**
     * 先落库再取基线，最后比出结论。
     *
     * <p>顺序要紧：基线按<b>写入顺序</b>定位（"我的前一条"），所以本条必须先入库拿到序号。
     * 早先按时间戳取基线时是反过来的（先查再写），但那个做法在同毫秒连跑两次时会取不到基线——
     * 评测是纯内存计算，这种情况在 CI 里必然发生。</p>
     */
    private EvalComparison persistAndCompare(EvalRun run) {
        store.save(run);
        EvalRun baseline = store.findBaseline(run.evalType(), run.runId()).orElse(null);
        EvalComparison comparison = EvalComparison.of(run, baseline);
        log.info("eval run finished: type={}, runId={}, primary={}, verdict={}, regressions={}",
            run.evalType(), run.runId(), String.format("%.4f", run.primaryMetric()),
            comparison.verdict(), comparison.regressions().size());
        return comparison;
    }

    /** 逐用例生成回复；单条失败不中断整轮，以空回复参与打分（会被 Judge 判低分，如实反映问题）。 */
    private List<String> generateReplies(CustomerServiceService chatService, List<QualityEvalCase> cases) {
        List<String> replies = new ArrayList<>(cases.size());
        for (QualityEvalCase evalCase : cases) {
            String sessionId = EVAL_SESSION_PREFIX + UUID.randomUUID();
            try {
                String reply = chatService.chat(sessionId, evalCase.input()).block();
                replies.add(reply == null ? "" : reply);
            } catch (Exception e) {
                log.error("[EvalService] generate reply failed, errorCode={}, caseId={}",
                    "EVAL-REPLY-FAIL", evalCase.id(), e);
                replies.add("");
            } finally {
                chatService.endSession(sessionId);
            }
        }
        return replies;
    }
}
