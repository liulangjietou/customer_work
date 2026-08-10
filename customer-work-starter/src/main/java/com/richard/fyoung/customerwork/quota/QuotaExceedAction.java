package com.richard.fyoung.customerwork.quota;

/**
 * 配额超额后的处置方式。
 *
 * <p>默认 {@link #BLOCK}：配额的意义就是硬上限。"软上限"该用
 * {@code warn_percent} 预警阈值表达，而不是让超额后继续花钱。</p>
 * @author owlzhangfq@gmail.com
 */
public enum QuotaExceedAction {

    /** 拦截：拒绝后续模型调用。拦得住成本，代价是服务中断。 */
    BLOCK,

    /**
     * 降级：改用备用（更便宜的）模型继续服务。
     *
     * <p>保住了服务可用性，但成本只是变慢不是停住——适合"宁可答得差也不能不答"的场景，
     * 不适合用来兜住真正的预算红线。</p>
     */
    DEGRADE,

    /** 仅告警：不拦不降级，只记录。用于观察期或内部租户。 */
    WARN;

    /** 宽松解析：脏值按 BLOCK 兜底（fail-closed，宁可误拦也不放任花钱）。 */
    public static QuotaExceedAction parse(String raw) {
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
