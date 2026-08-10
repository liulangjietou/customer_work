package com.richard.fyoung.customerwork.data.calllog;

/**
 * 调用日志分页 / 汇总 / 趋势的统一查询条件（全部可空，空即不约束该维度）。
 *
 * <p>{@code startFromMs}/{@code startToMs} 按主表 {@code start_time}（毫秒时间戳）过滤；
 * {@code offset}/{@code limit} 仅分页查询用，汇总与趋势忽略。</p>
 *
 * @param username    用户名（精确匹配）
 * @param agentCode   智能体编码（精确匹配）
 * @param requestId   请求 ID（精确匹配）
 * @param sessionId   会话 ID（精确匹配）
 * @param startFromMs 起始时间下界（毫秒，含）
 * @param startToMs   起始时间上界（毫秒，含）
 * @param offset      分页偏移（分页查询用）
 * @param limit       分页大小（分页查询用）
 * @author owlzhangfq@gmail.com
 */
public record AgentCallLogQuery(String username, String agentCode, String requestId, String sessionId,
                                Long startFromMs, Long startToMs, int offset, int limit) {

    /** 便捷构造：仅分页无过滤。 */
    public static AgentCallLogQuery page(int offset, int limit) {
        return new AgentCallLogQuery(null, null, null, null, null, null, offset, limit);
    }
}
