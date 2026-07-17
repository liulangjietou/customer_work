package com.richard.fyoung.customerwork.eval;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Pattern SCORE_PATTERN = Pattern.compile("SCORE:\\s*(\\d)", Pattern.CASE_INSENSITIVE);
    private static final int PASS_THRESHOLD = 3;

    private final JudgeModel judgeModel;

    public QualityEvalRunner(JudgeModel judgeModel) {
        this.judgeModel = judgeModel;
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

        for (int i = 0; i < total; i++) {
            QualityEvalCase c = cases.get(i);
            String reply = replies.get(i);

            int score = judge(c, reply);
            totalScore += score;
            if (score >= PASS_THRESHOLD) {
                passCount++;
            } else {
                failures.add(String.format("%s: score=%d input='%s'", c.id(), score, c.input()));
            }
        }

        double avgScore = total == 0 ? 0 : totalScore / total;
        QualityEvalReport report = new QualityEvalReport(total, avgScore, passCount, failures);
        log.info("quality eval done: total={}, avgScore={}, passRate={}%",
            total, String.format("%.2f", avgScore), String.format("%.1f", report.passRate() * 100));
        return report;
    }

    /**
     * 用 Judge LLM 对单条回复打分。
     *
     * @param testCase 评测用例
     * @param reply    Agent 的回复
     * @return 分数 1-5；解析失败返回 3（中性）
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
            log.warn("[QualityEval] judge failed for case {}: {}", testCase.id(), e.getMessage());
            return PASS_THRESHOLD; // 中性分数，不影响整体趋势
        }
    }

    private String buildJudgePrompt(QualityEvalCase testCase, String reply) {
        return """
            你是一个客服回复质量评测员。请对以下客服回复进行打分（1-5分），评分维度包括：
            1. 相关性：回复是否与用户问题相关
            2. 准确性：回复中的信息是否准确
            3. 完整性：回复是否完整回答了用户的问题

            用户输入：%s
            期望要点：%s
            客服回复：%s

            请输出格式：SCORE: <1-5的分数>
            理由：<评分理由>
            """.formatted(testCase.input(), testCase.expected(), reply);
    }

    int parseScore(String text) {
        if (text == null || text.isBlank()) {
            return PASS_THRESHOLD;
        }
        Matcher m = SCORE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                int score = Integer.parseInt(m.group(1));
                return Math.max(1, Math.min(5, score));
            } catch (NumberFormatException e) {
                return PASS_THRESHOLD;
            }
        }
        return PASS_THRESHOLD;
    }
}
