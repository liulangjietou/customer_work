package com.richard.fyoung.customerwork.capability.routing;

/**
 * 工单优先级（LLM 分类输出之一，以枚举名字符串落库）。
 *
 * <p>{@code weight} 供 {@link SeatRoutingScorer} 把优先级纳入打分（越紧急权重越大，score 量级随之放大，
 * 可解释）。未知/空值一律安全归 {@link #MEDIUM}（fast fail 收口在此，避免脏数据打断打分）。</p>
 * @author owlzhangfq@gmail.com
 */
public enum TicketPriority {

    LOW(1),
    MEDIUM(2),
    HIGH(3),
    URGENT(4);

    /** 最高权重（用于把优先级归一到 (0,1] 因子）。 */
    public static final int MAX_WEIGHT = 4;

    private final int weight;

    TicketPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    /** 安全解析：未知或空值一律归 {@link #MEDIUM}。 */
    public static TicketPriority fromName(String name) {
        if (name == null || name.isEmpty()) {
            return MEDIUM;
        }
        try {
            return TicketPriority.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
