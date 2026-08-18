package com.richard.fyoung.customerwork.safety.subjectquota;

/**
 * 主体配额判定结果。
 *
 * @param allowed       是否在额度内
 * @param kind          触顶的维度（放行时为 null）
 * @param action        超限处置（放行时为 null）
 * @param levelCode     判定所依据的等级（放行时可能为 null，表示没有适用等级）
 * @param used          判定时的已用量
 * @param limit         判定时的上限
 * @param windowSeconds 判定所用的滚动窗口长度
 * @author owlzhangfq@gmail.com
 */
public record SubjectQuotaDecision(boolean allowed,
                                   LimitKind kind,
                                   SubjectExceedAction action,
                                   String levelCode,
                                   long used,
                                   long limit,
                                   int windowSeconds) {

    /** 触顶的维度。 */
    public enum LimitKind {
        /** token 用量触顶。 */
        TOKEN,
        /** 请求次数触顶。 */
        REQUEST
    }

    private static final int SECONDS_PER_MINUTE = 60;

    public static SubjectQuotaDecision allow() {
        return new SubjectQuotaDecision(true, null, null, null, 0L, 0L, 0);
    }

    public static SubjectQuotaDecision exceeded(LimitKind kind, SubjectQuotaLevel level, long used) {
        long limit = kind == LimitKind.TOKEN ? level.tokenLimit() : level.requestLimit();
        return new SubjectQuotaDecision(false, kind, level.exceedAction(), level.levelCode(),
            used, limit, level.effectiveWindowSeconds());
    }

    /**
     * 是否应当拦下本次请求。
     *
     * <p>{@code WARN} 等级只记录不拦，故超限不等于拦截——这个区分是"新等级先观察一周再收紧"
     * 这种上线方式能成立的前提。</p>
     */
    public boolean shouldBlock() {
        return !allowed && action == SubjectExceedAction.BLOCK;
    }

    /**
     * 建议的重试等待秒数。
     *
     * <p>给的是窗口长度这个<b>上界</b>而非精确值：滚动窗口下额度是逐步释放的，真实恢复时刻取决于
     * 最早那笔用量何时出窗。与其算一个似是而非的精确值，不如给一个"最迟这么久一定能用"的保证。</p>
     */
    public int retryAfterSeconds() {
        return windowSeconds;
    }

    /** 用户可见的超限文案（中文，直接下发给终端用户）。 */
    public String message() {
        int minutes = Math.max(1, windowSeconds / SECONDS_PER_MINUTE);
        if (kind == LimitKind.REQUEST) {
            return "提问太频繁了：最近 " + minutes + " 分钟内已达 " + limit + " 次上限，请稍后再试。";
        }
        return "最近 " + minutes + " 分钟的用量额度已用完，请稍后再试或联系管理员提升额度。";
    }
}
