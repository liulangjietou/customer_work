package com.richard.fyoung.customerwork.capability.quality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话质检（借鉴 AliGo 质检/数据飞轮）：对坐席/Agent 的回复做合规与服务规范打分，离线确定性。
 *
 * <p>扣分项：</p>
 * <ul>
 *   <li><b>资金违规承诺</b>（已打款 / 立即到账 / 保证退 / 一定赔）——严重，单条 -40 且直接判不通过；</li>
 *   <li><b>绝对化表述</b>（绝对 / 100% / 肯定没问题）——单条 -10；</li>
 *   <li><b>服务禁语</b>（不知道 / 不可能 / 你自己）——单条 -15。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Service
public class QualityInspectionService {

    private static final Logger log = LoggerFactory.getLogger(QualityInspectionService.class);

    private static final String[] FUND_VIOLATIONS = {"已打款", "立即到账", "马上到账", "保证退", "一定赔"};
    private static final String[] ABSOLUTE_WORDS = {"绝对", "100%", "肯定没问题"};
    private static final String[] FORBIDDEN_WORDS = {"不知道", "不可能", "你自己"};

    private static final int PASS_LINE = 60;
    private static final int PENALTY_FUND = 40;
    private static final int PENALTY_ABSOLUTE = 10;
    private static final int PENALTY_FORBIDDEN = 15;

    /** 对一组（坐席/Agent）回复做质检。 */
    public QualityReport inspect(List<String> replies) {
        int score = 100;
        boolean fundViolation = false;
        List<String> issues = new ArrayList<>();
        List<String> lines = replies == null ? List.of() : replies;

        for (String reply : lines) {
            if (reply == null) {
                continue;
            }
            for (String w : FUND_VIOLATIONS) {
                if (reply.contains(w)) {
                    score -= PENALTY_FUND;
                    fundViolation = true;
                    issues.add("资金违规承诺：「" + w + "」 in 「" + reply + "」");
                }
            }
            for (String w : ABSOLUTE_WORDS) {
                if (reply.contains(w)) {
                    score -= PENALTY_ABSOLUTE;
                    issues.add("绝对化表述：「" + w + "」");
                }
            }
            for (String w : FORBIDDEN_WORDS) {
                if (reply.contains(w)) {
                    score -= PENALTY_FORBIDDEN;
                    issues.add("服务禁语：「" + w + "」");
                }
            }
        }
        int finalScore = Math.max(0, score);
        boolean passed = finalScore >= PASS_LINE && !fundViolation;
        log.info("quality inspect: score={}, passed={}, issues={}", finalScore, passed, issues.size());
        return new QualityReport(finalScore, passed, issues);
    }
}
