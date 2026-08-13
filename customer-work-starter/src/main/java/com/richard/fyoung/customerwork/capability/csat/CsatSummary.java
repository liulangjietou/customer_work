package com.richard.fyoung.customerwork.capability.csat;

import java.util.List;

/**
 * CSAT 汇总指标。
 *
 * @param invited   邀请数
 * @param answered  回收数（实际评了分的）
 * @param satisfied 满意数（4 分及以上）
 * @param totalScore 分数总和（用于算平均分）
 * @author owlzhangfq@gmail.com
 */
public record CsatSummary(long invited, long answered, long satisfied, long totalScore) {

    /**
     * CSAT 得分：满意数 / 回收数。
     *
     * <p>行业标准口径，不是平均分——平均分会被大量 3 分（无感）拉成一个看着还行的数字，
     * 把真正不满的那批人掩盖掉。</p>
     */
    public double csat() {
        return answered == 0 ? 0.0d : (double) satisfied / answered;
    }

    /**
     * 回收率：回收数 / 邀请数。
     *
     * <p>必须和 CSAT 一起看：回收率过低时，评分只代表那一小撮愿意评价的人——
     * 而愿意主动评价的往往是特别满意或特别不满的两头，中间的沉默大多数不在样本里。</p>
     */
    public double responseRate() {
        return invited == 0 ? 0.0d : (double) answered / invited;
    }

    /** 平均分（1-5）；辅助看，主指标仍是 {@link #csat()}。 */
    public double averageScore() {
        return answered == 0 ? 0.0d : (double) totalScore / answered;
    }

    /** 由一批调查记录聚合。 */
    public static CsatSummary of(List<CsatSurvey> surveys) {
        long invited = surveys.size();
        long answered = 0;
        long satisfied = 0;
        long totalScore = 0;
        for (CsatSurvey survey : surveys) {
            if (!survey.answered()) {
                continue;
            }
            answered++;
            totalScore += survey.score();
            if (survey.satisfied()) {
                satisfied++;
            }
        }
        return new CsatSummary(invited, answered, satisfied, totalScore);
    }
}
