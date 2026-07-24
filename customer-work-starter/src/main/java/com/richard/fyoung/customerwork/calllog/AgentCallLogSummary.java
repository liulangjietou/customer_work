package com.richard.fyoung.customerwork.calllog;

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
 * @author owlzhangfq@gmail.com
 */
public record AgentCallLogSummary(long totalCount, double avgDurationMs, long maxDurationMs,
                                  double avgModelMs, double avgToolMs, double avgMcpMs,
                                  double avgSkillMs) {

    /** 空结果（无数据时返回，避免 null）。 */
    public static AgentCallLogSummary empty() {
        return new AgentCallLogSummary(0L, 0d, 0L, 0d, 0d, 0d, 0d);
    }
}
