package com.richard.fyoung.customerwork.data.calllog;

import java.util.List;

/**
 * 一次智能体调用的完整记录（不可变，主表 {@code cw_agent_call_log} + 明细段 {@code cw_agent_call_segment}）。
 *
 * <p>{@code id} 为存储层自增主键（保存前为 0，由存储层回填）。{@code modelMs/toolMs/mcpMs/skillMs} 为四类
 * 分段耗时汇总，{@code segmentCount} 为分段总数，均在 {@link AgentCallCollector#toRecord} 组装时按
 * {@link #segments} 计算，落库时冗余存储以便报表免关联明细即可出各段汇总。</p>
 *
 * <p>{@code inputTokens/outputTokens/totalTokens} 为请求级 token 消耗汇总（各 MODEL 段 usage 求和，
 * 与 admin 审计模块同源同口径）；本次调用无任何 usage 时三者均为 {@code null}（区分"未采到"与"用了 0 token"）。</p>
 *
 * @param id           存储层自增主键（保存前为 0）
 * @param requestId    请求 ID
 * @param userId       用户 ID（ctx.getUserId()）
 * @param username     用户名
 * @param agentCode    智能体编码
 * @param agentName    智能体名称
 * @param sessionId    会话 ID
 * @param sessionType  会话类型
 * @param question     用户问题
 * @param answer       智能体回答
 * @param startTimeMs  调用开始时间戳（毫秒）
 * @param endTimeMs    调用结束时间戳（毫秒）
 * @param durationMs   总耗时（毫秒）
 * @param modelMs      MODEL 段耗时合计（毫秒）
 * @param toolMs       TOOL 段耗时合计（毫秒）
 * @param mcpMs        MCP 段耗时合计（毫秒）
 * @param skillMs      SKILL 段耗时合计（毫秒）
 * @param segmentCount 分段总数
 * @param inputTokens  请求级输入 token 合计（缺失为 null）
 * @param outputTokens 请求级输出 token 合计（缺失为 null）
 * @param totalTokens  请求级总 token 合计（缺失为 null）
 * @param cachedTokens 请求级命中缓存的输入 token 合计（<b>inputTokens 的子集，不计入 totalTokens</b>，缺失为 null）
 * @param modelReportedMs 各 MODEL 段模型自报耗时之和（毫秒，缺失为 null）
 * @param success      整次调用是否成功
 * @param errorMsg     失败原因（成功时为 null）
 * @param segments     分段明细（按 seq 升序）
 * @author owlzhangfq@gmail.com
 */
public record AgentCallRecord(long id, String requestId, String userId, String username,
                              String agentCode, String agentName, String sessionId,
                              AgentCallSessionType sessionType, String question, String answer,
                              long startTimeMs, long endTimeMs, long durationMs,
                              long modelMs, long toolMs, long mcpMs, long skillMs,
                              int segmentCount, Long inputTokens, Long outputTokens, Long totalTokens,
                              Long cachedTokens, Long modelReportedMs,
                              boolean success, String errorMsg,
                              List<AgentCallSegment> segments) {

    /** 回填自增主键后的副本。 */
    public AgentCallRecord withId(long assignedId) {
        return new AgentCallRecord(assignedId, requestId, userId, username, agentCode, agentName,
            sessionId, sessionType, question, answer, startTimeMs, endTimeMs, durationMs,
            modelMs, toolMs, mcpMs, skillMs, segmentCount, inputTokens, outputTokens, totalTokens,
            cachedTokens, modelReportedMs, success, errorMsg, segments);
    }

    /**
     * 缓存命中率（{@code cachedTokens / inputTokens}）；无输入 token 或未采到缓存量时返回 null。
     *
     * <p>放在领域对象上而不是让每个展示端各算一遍：命中率是"缓存有没有真生效"的唯一直接信号，
     * 分子分母的口径（缓存量是输入的子集）一旦在某处理解错，算出来的数就会误导决策。</p>
     */
    public Double cacheHitRate() {
        if (cachedTokens == null || inputTokens == null || inputTokens == 0L) {
            return null;
        }
        return (double) cachedTokens / (double) inputTokens;
    }
}
