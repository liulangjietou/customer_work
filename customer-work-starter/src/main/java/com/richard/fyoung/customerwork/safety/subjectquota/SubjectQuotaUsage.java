package com.richard.fyoung.customerwork.safety.subjectquota;

/**
 * 主体的当前用量快照（给终端用户看"我还剩多少"、给排障看"他到底用了多少"）。
 *
 * <p>不含"何时恢复"：滚动窗口下额度是连续释放的，任何一个恢复时刻都只对某一笔用量成立，
 * 报出去只会变成客服要解释的新问题。</p>
 *
 * @param levelCode     生效等级；无适用等级时为 null，此时各项上限均为 0（= 不限）
 * @param windowSeconds 滚动窗口长度
 * @param tokenUsed     窗口内已用 token
 * @param tokenLimit    token 上限，0 = 不限
 * @param requestUsed   窗口内已发起请求数
 * @param requestLimit  次数上限，0 = 不限
 * @author owlzhangfq@gmail.com
 */
public record SubjectQuotaUsage(String levelCode,
                                int windowSeconds,
                                long tokenUsed,
                                long tokenLimit,
                                long requestUsed,
                                long requestLimit) {

    /** 无适用等级（功能关闭或该主体不受限）时的空快照。 */
    public static SubjectQuotaUsage unlimited() {
        return new SubjectQuotaUsage(null, 0, 0L, 0L, 0L, 0L);
    }

    /** 剩余 token（不限时返回 -1，调用方据此区分"还剩 0"与"本来就不限"）。 */
    public long tokenRemaining() {
        return tokenLimit <= 0 ? -1L : Math.max(0L, tokenLimit - tokenUsed);
    }

    /** 剩余次数（不限时返回 -1）。 */
    public long requestRemaining() {
        return requestLimit <= 0 ? -1L : Math.max(0L, requestLimit - requestUsed);
    }
}
