package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

/** Judge 调用异常时的发布策略。 */
public enum JudgeErrorPolicy {
    /** Judge 异常即阻断，生产推荐。 */
    BLOCK,
    /** 明确跳过该质量评测规则；只适合已有其它强门禁的场景。 */
    ALLOW
}
