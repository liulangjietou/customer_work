package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.harness.agent.HarnessAgent;

import java.util.Objects;

/**
 * 把 Harness 默认开启的可选能力收敛为项目侧显式开启语义。
 *
 * <p>AgentScope Harness 2.0 默认带文件、Shell、上下文压缩、工具结果落盘、子智能体和动态技能能力。
 * 项目配置与智能体 capabilities 均采用 opt-in 语义；未配置时若不显式关闭，不仅会扩大工具 Schema，
 * 还可能触发未声明的模型调用或写操作。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class HarnessOptInPolicy {

    private static final String WAIT_ASYNC_RESULTS_TOOL = "wait_async_results";

    private HarnessOptInPolicy() {
    }

    /** 根据项目侧能力开关覆盖 Harness 默认值。 */
    public static void apply(HarnessAgent.Builder builder,
                             boolean filesystemEnabled,
                             boolean compactionEnabled,
                             boolean toolResultEvictionEnabled,
                             boolean subagentsEnabled,
                             boolean dynamicSubagentsEnabled,
                             boolean dynamicSkillsEnabled) {
        Objects.requireNonNull(builder, "builder");
        if (!filesystemEnabled) {
            builder.disableFilesystemTools();
            builder.disableShellTool();
        }
        if (!compactionEnabled) {
            builder.disableCompaction();
        }
        if (!toolResultEvictionEnabled) {
            builder.disableToolResultEviction();
        }
        if (!subagentsEnabled && !dynamicSubagentsEnabled) {
            builder.disableSubagents();
        } else if (!dynamicSubagentsEnabled) {
            builder.disableDynamicSubagents();
        }
        if (!dynamicSkillsEnabled) {
            builder.disableDynamicSkills();
        }
    }

    /**
     * 清理 Builder 关闭能力后仍被框架无条件注册的工具。
     *
     * <p>Harness 2.0 即使 {@code disableSubagents()} 仍会留下 {@code wait_async_results}；没有任何
     * 静态或动态子 Agent 时该工具既不可产生结果，也不应继续占用模型上下文。</p>
     */
    public static void pruneBuiltInTools(HarnessAgent agent, boolean subagentsEnabled,
                                         boolean dynamicSubagentsEnabled) {
        Objects.requireNonNull(agent, "agent");
        if (!subagentsEnabled && !dynamicSubagentsEnabled) {
            agent.getToolkit().removeTool(WAIT_ASYNC_RESULTS_TOOL);
        }
    }
}
