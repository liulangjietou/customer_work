package com.richard.fyoung.customerwork.safety.subjectquota;

/**
 * 配额等级（不可变值对象）：一档额度的完整定义。
 *
 * <p>语义：属于本等级的主体，在最近 {@code windowSeconds} 秒内最多消耗 {@code tokenLimit} 个 token、
 * 发起 {@code requestLimit} 次请求，任一维度触顶即按 {@code exceedAction} 处置。两个上限
 * <b>各自独立</b>——token 拦的是"一次问得太重"，次数拦的是"问得太频繁"，一个拦不住另一个。</p>
 *
 * <p>窗口是<b>滚动</b>的（最近 N 秒），不是自然对齐的。与租户配额（自然日/月，要跟账单对齐）
 * 刻意不同：防滥用不需要跟账单对齐，而整点归零会让刷量方掐着点把额度用两遍。</p>
 *
 * @param id            主键（JDBC 实现回填）
 * @param tenantId      归属租户
 * @param levelCode     等级编码（如 {@code free}/{@code vip}），租户内唯一
 * @param levelName     等级名称（运营可读）
 * @param subjectType   适用的主体类型
 * @param windowSeconds 滚动窗口长度（秒），如 1800 = 30 分钟
 * @param tokenLimit    窗口内 token 上限，0 = 不限
 * @param requestLimit  窗口内请求次数上限，0 = 不限
 * @param exceedAction  超限处置
 * @param enabled       是否启用；停用等于该等级的主体不受限
 * @param remark        备注
 * @author owlzhangfq@gmail.com
 */
public record SubjectQuotaLevel(Long id,
                                String tenantId,
                                String levelCode,
                                String levelName,
                                QuotaSubjectType subjectType,
                                int windowSeconds,
                                long tokenLimit,
                                int requestLimit,
                                SubjectExceedAction exceedAction,
                                boolean enabled,
                                String remark) {

    /** 窗口长度缺失时的兜底（30 分钟）：等级配了却没配窗口时，按最常用的档走而不是当成"不限"。 */
    public static final int DEFAULT_WINDOW_SECONDS = 1800;

    /** 是否设置了 token 上限（0 表示不限，不参与判定）。 */
    public boolean hasTokenLimit() {
        return tokenLimit > 0;
    }

    /** 是否设置了次数上限（0 表示不限，不参与判定）。 */
    public boolean hasRequestLimit() {
        return requestLimit > 0;
    }

    /** 实际生效的窗口长度：非正值一律按默认档，避免"0 秒窗口"把计数器变成永远超限。 */
    public int effectiveWindowSeconds() {
        return windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
    }

    /** 是否真的会拦住任何东西：停用或两个上限都为 0 时，这一档等于不存在。 */
    public boolean effective() {
        return enabled && (hasTokenLimit() || hasRequestLimit());
    }
}
