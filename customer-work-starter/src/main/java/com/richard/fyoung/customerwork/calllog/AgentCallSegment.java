package com.richard.fyoung.customerwork.calllog;

/**
 * 智能体一次调用中的一段耗时明细（不可变）。
 *
 * <p>一段对应一次 onModelCall（{@link AgentCallKind#MODEL}）或一次 onActing（工具执行，按
 * {@link ToolKindRegistry} 归为 TOOL/MCP/SKILL）。{@code seq} 为该次调用内的分段序号（从 1 起，
 * 按结算顺序自增）；{@code startTimeMs} 为分段开始的墙上时钟（毫秒），{@code durationMs} 为分段耗时。</p>
 *
 * <p>{@code inputTokens}/{@code outputTokens} 仅 MODEL 段有值（取自框架 {@code ModelCallEndEvent}
 * 携带的 {@code ChatUsage}，与 admin 审计模块同源同口径）；工具段及 usage 缺失时为 {@code null}。</p>
 *
 * @param seq          调用内分段序号（从 1 起）
 * @param kind         分段类别
 * @param name         分段名称（模型名 / 工具名）
 * @param startTimeMs  分段开始时间戳（毫秒）
 * @param durationMs   分段耗时（毫秒）
 * @param success      是否成功
 * @param errorMsg     失败原因（成功时为 null）
 * @param inputTokens  输入 token（仅 MODEL 段，缺失为 null）
 * @param outputTokens 输出 token（仅 MODEL 段，缺失为 null）
 * @author owlzhangfq@gmail.com
 */
public record AgentCallSegment(int seq, AgentCallKind kind, String name, long startTimeMs,
                               long durationMs, boolean success, String errorMsg,
                               Long inputTokens, Long outputTokens) {
}
