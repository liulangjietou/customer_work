package com.richard.fyoung.customerwork.data.calllog;

/**
 * 调用日志汇总统计（符合查询条件的整体口径）。
 *
 * @param totalCount    总调用数
 * @param avgDurationMs 平均总耗时（毫秒）
 * @param maxDurationMs 最大总耗时（毫秒）
 * @param avgModelMs    平均 MODEL 段耗时（毫秒）
 * @param avgToolMs     平均 TOOL 段耗时（毫秒）
 * @param avgMcpMs      平均 MCP 段耗时（毫秒）
 * @param avgSkillMs    平均 SKILL 段耗时（毫秒）
 * @param totalTokens   总 token 消耗（符合条件的记录 total_tokens 求和）
 * @param avgTotalTokens 平均每次 token 消耗
 * @param inputTokens   总输入 token（算缓存命中率的分母）
 * @param cachedTokens  总命中缓存的输入 token（<b>inputTokens 的子集</b>，各家通常按 1/10 计价）
 * @author owlzhangfq@gmail.com
 */
public record AgentCallLogSummary(long totalCount, double avgDurationMs, long maxDurationMs,
                                  double avgModelMs, double avgToolMs, double avgMcpMs,
                                  double avgSkillMs, long totalTokens, double avgTotalTokens,
                                  long inputTokens, long cachedTokens) {

    /** 空结果（无数据时返回，避免 null）。 */
    public static AgentCallLogSummary empty() {
        return new AgentCallLogSummary(0L, 0d, 0L, 0d, 0d, 0d, 0d, 0L, 0d, 0L, 0L);
    }

    /**
     * 缓存命中率（{@code cachedTokens / inputTokens}）；无输入 token 时返回 0。
     *
     * <p>这是判断 prompt 缓存有没有真生效的唯一直接信号：命中率长期为 0，说明系统提示词或历史消息
     * 每次都在变，缓存根本没建立起来——那笔本可省下的钱一直在白花。</p>
     */
    public double cacheHitRate() {
        return inputTokens == 0L ? 0d : (double) cachedTokens / (double) inputTokens;
    }
}
