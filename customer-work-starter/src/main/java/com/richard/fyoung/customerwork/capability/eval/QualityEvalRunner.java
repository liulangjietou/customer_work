package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回复质量评测执行器（LLM-as-Judge，P3）。
 *
 * <p>用 LLM 对 Agent 回复进行质量打分（1-5 分），量化回复的相关性、准确性、完整性。
 * 与 {@link IntentEvalRunner}（离线确定性评测）互补：意图评测可离线跑，质量评测需真实模型 Key。</p>
 *
 * <p>评测流程：</p>
 * <ol>
 *   <li>对每个用例，先用 Agent 生成回复（或使用预置回复）；</li>
 *   <li>把用户输入、Agent 回复、期望要点提交给 Judge LLM 打分；</li>
 *   <li>解析 Judge 输出中的分数（1-5），汇总报告。</li>
 * </ol>
 *
 * <p>Judge Prompt 约定：输出格式为 {@code SCORE: <1-5>}，后跟评分理由。</p>
 * @author owlzhangfq@gmail.com
 */
public class QualityEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(QualityEvalRunner.class);

    private static final Pattern SCORE_PATTERN = Pattern.compile(
        "SCORE:\\s*([1-5])(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final int PASS_THRESHOLD = 3;

    private static final String DATASET_PATH = "eval/quality-eval-cases.json";

    private static final String JUDGE_RUBRIC_TEMPLATE = """
        你是一个客服回复质量评测员。请对以下客服回复进行打分（1-5分），评分维度包括：
        1. 相关性：回复是否与用户问题相关
        2. 准确性：回复中的信息是否准确
        3. 完整性：回复是否完整回答了用户的问题

        用户输入：%s
        期望要点：%s
        客服回复：%s

        请输出格式：SCORE: <1-5的分数>
        理由：<评分理由>
        """;

    private final JudgeModel judgeModel;
    private final EvalCaseStore caseStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QualityEvalRunner(JudgeModel judgeModel, EvalCaseStore caseStore) {
        this.judgeModel = judgeModel;
        this.caseStore = caseStore;
    }

    /** 便捷重载：只用 classpath 种子（单测与离线试跑用）。 */
    public QualityEvalRunner(JudgeModel judgeModel) {
        this(judgeModel, new InMemoryEvalCaseStore());
    }

    /**
     * 加载质量评测集（与 {@link IntentEvalRunner#loadDataset()} 对称）：
     * classpath 种子 + 库中增量，同 ID 以库为准，停用的剔除。
     *
     * <p>种子加载不到直接抛错而不是返回空集——空集会算出一份 0 用例的"满分"报告，比报错更难发现。</p>
     */
    public List<QualityEvalCase> loadDataset() {
        List<PersistedEvalCase> merged =
            EvalDatasetMerger.merge(loadSeeds(), caseStore.findByType(EvalType.QUALITY));
        List<QualityEvalCase> cases = new ArrayList<>(merged.size());
        for (PersistedEvalCase evalCase : merged) {
            cases.add(evalCase.toQualityCase());
        }
        return List.copyOf(cases);
    }

    /** 读 classpath 种子并统一成存储形状，便于与库中用例合并。 */
    private List<PersistedEvalCase> loadSeeds() {
        try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
            QualityEvalCase[] seeds = objectMapper.readValue(in, QualityEvalCase[].class);
            List<PersistedEvalCase> result = new ArrayList<>(seeds.length);
            for (QualityEvalCase seed : seeds) {
                result.add(new PersistedEvalCase(seed.id(), EvalType.QUALITY, seed.input(),
                    seed.expected(), seed.category(), EvalCaseSource.SEED, true, null, 0L));
            }
            return result;
        } catch (Exception e) {
            log.error("load quality eval dataset failed, errorCode={}, path={}", EvalErrorCodes.LOAD_FAIL, DATASET_PATH, e);
            throw new IllegalStateException("quality eval dataset not loadable: " + DATASET_PATH, e);
        }
    }

    /**
     * 对给定用例列表逐条评测。
     *
     * @param cases    评测用例
     * @param replies  对应的 Agent 回复（与 cases 等长、同序）
     * @return 质量评测报告
     */
    public QualityEvalReport run(List<QualityEvalCase> cases, List<String> replies) {
        if (cases.size() != replies.size()) {
            throw new IllegalArgumentException("cases 和 replies 长度不一致");
        }
        int total = cases.size();
        double totalScore = 0;
        int passCount = 0;
        List<String> failures = new ArrayList<>();
        List<String> failedCaseIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> errorCaseIds = new ArrayList<>();
        int judgedCount = 0;

        for (int i = 0; i < total; i++) {
            QualityEvalCase c = cases.get(i);
            String reply = replies.get(i);

            try {
                int score = judge(c, reply);
                judgedCount++;
                totalScore += score;
                if (score >= PASS_THRESHOLD) {
                    passCount++;
                } else {
                    failures.add(String.format("%s: score=%d input='%s'", c.id(), score, c.input()));
                    failedCaseIds.add(c.id());
                }
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                errors.add(String.format("%s: status=ERROR input='%s' reason='%s'", c.id(), c.input(), reason));
                errorCaseIds.add(c.id());
            }
        }

        double avgScore = judgedCount == 0 ? 0 : totalScore / judgedCount;
        QualityEvalReport report = new QualityEvalReport(total, avgScore, passCount, failures, failedCaseIds,
            judgedCount, errors, errorCaseIds);
        log.info("quality eval done: status={}, total={}, judged={}, errors={}, avgScore={}, passRate={}%",
            report.getStatus(), total, judgedCount, report.getErrorCount(), String.format("%.2f", avgScore),
            String.format("%.1f", report.passRate() * 100));
        return report;
    }

    /**
     * 用 Judge LLM 对单条回复打分。
     *
     * @param testCase 评测用例
     * @param reply    Agent 的回复
     * @return 分数 1-5；模型或格式异常直接抛出，由整轮报告记录为 ERROR
     */
    int judge(QualityEvalCase testCase, String reply) {
        String prompt = buildJudgePrompt(testCase, reply);
        try {
            Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(prompt).build())
                .build();
            Msg response = judgeModel.chat(msg);
            String text = response == null ? "" : response.getTextContent();
            return parseScore(text);
        } catch (Exception e) {
            log.error("[QualityEval] judge failed, errorCode={}, caseId={}",
                "EVAL-JUDGE-FAIL", testCase.id(), e);
            throw new IllegalStateException("judge unavailable: " + errorMessage(e), e);
        }
    }

    private String buildJudgePrompt(QualityEvalCase testCase, String reply) {
        return JUDGE_RUBRIC_TEMPLATE.formatted(testCase.input(), testCase.expected(), reply);
    }

    /** Judge 提示词与通过线共同组成 rubric 版本，任一变化都会产生新指纹。 */
    public static String rubricVersion() {
        return EvalFingerprint.of(JUDGE_RUBRIC_TEMPLATE, PASS_THRESHOLD, SCORE_PATTERN.pattern());
    }

    int parseScore(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("judge returned empty response");
        }
        Matcher m = SCORE_PATTERN.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        throw new IllegalArgumentException("judge response missing SCORE: <1-5>");
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
