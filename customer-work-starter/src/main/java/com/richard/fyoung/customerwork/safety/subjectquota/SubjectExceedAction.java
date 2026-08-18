package com.richard.fyoung.customerwork.safety.subjectquota;

/**
 * 主体配额超限后的处置方式。
 *
 * <p><b>刻意不复用租户配额的 {@code QuotaExceedAction}</b>：那个枚举有 {@code DEGRADE}（改用更便宜的模型），
 * 而主体配额判定发生在接入层——那里既不知道会用哪个模型，也没有换模型的手段。把一个必然不生效的选项
 * 摆在后台下拉框里，只会让运营配了一个"看起来在省钱、实际什么都没做"的策略。</p>
 * @author owlzhangfq@gmail.com
 */
public enum SubjectExceedAction {

    /** 拦截：直接拒绝本次请求。防滥用的默认答案。 */
    BLOCK,

    /** 仅告警：不拦，只记录一条超限命中。用于新等级上线前的观察期。 */
    WARN;

    /** 宽松解析：脏值按 {@link #BLOCK} 兜底（fail-closed，宁可误拦也不放任刷量）。 */
    public static SubjectExceedAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BLOCK;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return BLOCK;
        }
    }
}
