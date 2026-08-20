package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 上下文控制配置。
 *
 * <p>包含两套互补的机制，作用在<b>不同的 Agent 形态</b>上，别混用：</p>
 * <ul>
 *   <li>{@code compressionEnabled} —— Harness 的 {@code CompactionConfig}（模型驱动的智能压缩，
 *       调模型把历史总结成摘要）。<b>只对 {@code HarnessAgent} 生效</b>，框架层面挂不到
 *       主对话链路的 {@code ReActAgent} 上；</li>
 *   <li>{@code budgetEnabled} —— 本项目实现的 {@code ContextBudgetMiddleware}（确定性裁剪，
 *       不调模型、不产生额外成本）。中间件形态，经 {@code AgentGovernanceAssembler}
 *       在<b>所有</b>对话路径生效。主链路的上下文有界靠它。</li>
 * </ul>
 */
@Data
public class ContextProperties {
    /** 是否启用自动上下文压缩（长对话上下文有界）。默认关闭，开启需可用模型。仅 HarnessAgent 生效。 */
    private boolean compressionEnabled = false;
    /** 触发压缩的最大 token 阈值。 */
    private long maxToken = 8000;
    /** 触发压缩的消息条数阈值。 */
    private int msgThreshold = 40;
    /** 压缩时保留最近 N 条消息原文。 */
    private int lastKeep = 10;

    /**
     * 是否启用确定性上下文预算裁剪（{@code ContextBudgetMiddleware}，所有对话路径生效）。
     *
     * <p>默认关闭：裁剪会丢弃较早的历史，是否可接受取决于业务对长程记忆的依赖程度。
     * 但<b>不开就没有任何上限</b>——长会话叠加 RAG 召回与工具结果会一路涨到模型报错为止，
     * 生产部署建议显式开启并按所用模型的上下文窗口设置 {@link #budgetMaxMessages}。</p>
     */
    private boolean budgetEnabled = false;

    /**
     * 预算裁剪保留的最大消息条数（不含 system 消息，system 永远保留）。
     *
     * <p>裁剪时<b>丢中间、保两头</b>：最早的几条常含用户诉求的关键背景，最近的几条是当前话题，
     * 中间部分最适合牺牲。</p>
     */
    private int budgetMaxMessages = 60;

    /** 预算裁剪时保底保留的最早消息条数（对话开头的背景信息）。 */
    private int budgetKeepEarliest = 4;
}
