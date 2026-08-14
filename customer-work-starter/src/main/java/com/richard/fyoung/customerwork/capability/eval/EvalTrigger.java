package com.richard.fyoung.customerwork.capability.eval;

/**
 * 评测触发来源。
 *
 * <p>记录来源不是为了统计好看，而是为了解释指标突变：同一份评测集，定时跑与人工跑
 * 之间夹着一次提示词发布，指标掉了才知道该去看哪次改动。</p>
 * @author owlzhangfq@gmail.com
 */
public enum EvalTrigger {

    /** 人工在后台点触发。 */
    MANUAL,

    /** 定时任务触发（每日基线）。 */
    SCHEDULED,

    /** 外部接口触发（CI 门禁 / 发布流水线）。 */
    API
}
