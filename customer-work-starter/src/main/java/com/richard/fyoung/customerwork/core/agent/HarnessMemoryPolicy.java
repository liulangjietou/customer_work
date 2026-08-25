package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;

import java.util.Objects;

/**
 * Harness 长期记忆能力策略。
 *
 * <p>AgentScope Harness 2.0 默认注册记忆工具，并在每轮调用后执行 memory flush / consolidation。
 * 项目侧的记忆能力是显式开启语义，因此不能只在开启时设置 {@link MemoryConfig}，关闭时也必须覆盖
 * 框架默认值，避免未声明 memory 能力的 Agent 产生隐藏模型调用和记忆文件。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class HarnessMemoryPolicy {

    private HarnessMemoryPolicy() {
    }

    /**
     * 让 Harness 的实际记忆行为与项目配置保持一致。
     *
     * @param builder Harness 构建器
     * @param enabled 是否显式启用长期记忆
     * @param model   记忆提取与整理使用的模型；仅在启用时要求非空
     */
    public static void apply(HarnessAgent.Builder builder, boolean enabled, Model model) {
        Objects.requireNonNull(builder, "builder");
        if (enabled) {
            builder.memory(MemoryConfig.builder()
                .model(Objects.requireNonNull(model, "model"))
                .build());
            return;
        }
        builder.disableMemoryHooks();
        builder.disableMemoryTools();
    }
}
