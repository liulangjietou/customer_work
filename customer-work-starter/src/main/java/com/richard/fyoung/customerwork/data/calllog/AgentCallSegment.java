package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.core.model.attribution.ModelCallAttribution;
import com.richard.fyoung.customerwork.core.model.attribution.ModelCallCost;

/**
 * 智能体一次调用中的一段耗时明细（不可变）。
 *
 * <p>一段对应一次 onModelCall（{@link AgentCallKind#MODEL}）或一次 onActing（工具执行，按
 * {@link ToolKindRegistry} 归为 TOOL/MCP/SKILL）。{@code seq} 为该次调用内的分段序号（从 1 起，
 * 按结算顺序自增）；{@code startTimeMs} 为分段开始的墙上时钟（毫秒），{@code durationMs} 为分段耗时。</p>
 *
 * <p>token 与模型自报耗时仅 MODEL 段有值（取自框架 {@code ModelCallEndEvent} 携带的 {@code ChatUsage}，
 * 与 admin 审计模块同源同口径）；工具段及 usage 缺失时为 {@code null}。</p>
 *
 * <p><b>{@code cachedTokens} 是 {@code inputTokens} 的子集，不是额外量</b>——命中 prompt 缓存的那部分
 * 输入 token。各家计价通常只按 1/10 左右收，不单独留存就没法做准确的成本核算，也看不出缓存到底有没有生效。</p>
 *
 * <p><b>{@code modelReportedMs} 是模型自报的本次调用耗时</b>（{@code ChatUsage.time}，秒 → 毫秒），
 * 与本段实测的 {@code durationMs} 是两个口径：两者之差就是网络与排队开销，能把"模型慢"和"链路慢"分开。</p>
 *
 * @param seq             调用内分段序号（从 1 起）
 * @param kind            分段类别
 * @param name            分段名称（模型名 / 工具名）
 * @param startTimeMs     分段开始时间戳（毫秒）
 * @param durationMs      分段耗时（毫秒，本地实测）
 * @param success         是否成功
 * @param errorMsg        失败原因（成功时为 null）
 * @param inputTokens     输入 token（仅 MODEL 段，缺失为 null）
 * @param outputTokens    输出 token（仅 MODEL 段，缺失为 null）
 * @param cachedTokens    命中缓存的输入 token（inputTokens 的子集，仅 MODEL 段，缺失为 null）
 * @param modelReportedMs 模型自报耗时（毫秒，仅 MODEL 段，缺失为 null）
 * @author owlzhangfq@gmail.com
 */
public record AgentCallSegment(int seq, AgentCallKind kind, String name, long startTimeMs,
                               long durationMs, boolean success, String errorMsg,
                               Long inputTokens, Long outputTokens,
                               Long cachedTokens, Long modelReportedMs,
                               ModelCallAttribution attribution) {

    /**
     * 按本分段冻结的价目与真实 usage 生成金额事实。非 MODEL 分段明确返回 NOT_APPLICABLE。
     */
    public ModelCallCost cost() {
        return kind == AgentCallKind.MODEL
            ? ModelCallCost.settle(attribution, inputTokens, outputTokens, cachedTokens)
            : ModelCallCost.notApplicable();
    }

    /** 兼容工具分段与旧调用方；模型采集入口会始终传入明确的 PRICED/UNPRICED 快照。 */
    public AgentCallSegment(int seq, AgentCallKind kind, String name, long startTimeMs,
                            long durationMs, boolean success, String errorMsg,
                            Long inputTokens, Long outputTokens,
                            Long cachedTokens, Long modelReportedMs) {
        this(seq, kind, name, startTimeMs, durationMs, success, errorMsg,
            inputTokens, outputTokens, cachedTokens, modelReportedMs, null);
    }
}
