package com.richard.fyoung.customeradmin.workspace.task.runtime;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * 单次 Agent 调用内，{@code agent_spawn(timeout_seconds=0)} 参数到 TaskRepository 的桥梁。
 *
 * <p>AgentScope 2.0 的 {@code LocalTaskRunSpec} 只携带不可序列化的 Supplier，仓储看不到原始提示词。
 * Acting 中间件先把模型已经确定的工具参数放进本上下文，框架随后调用仓储时按 subAgentId 消费，
 * 从而在不复制/反射框架内部实现的前提下得到可持久化、可重放的最小执行描述。</p>
 */
public final class AgentTaskReplayContext {

    private final ConcurrentLinkedDeque<ReplaySpec> pending = new ConcurrentLinkedDeque<>();

    public void offer(ReplaySpec spec) {
        pending.addLast(Objects.requireNonNull(spec, "spec"));
    }

    /** Toolkit 默认顺序执行工具；同一 subAgentId 的多次提交按模型工具调用顺序消费。 */
    public ReplaySpec claim(String subAgentId) {
        for (ReplaySpec spec : pending) {
            if (Objects.equals(spec.subAgentId(), subAgentId) && pending.remove(spec)) {
                return spec;
            }
        }
        return null;
    }

    /** 工具执行失败时移除尚未被仓储消费的参数，避免污染下一轮 Acting。 */
    public void discard(Collection<String> toolCallIds) {
        if (toolCallIds == null || toolCallIds.isEmpty()) {
            return;
        }
        Set<String> ids = toolCallIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        pending.removeIf(spec -> ids.contains(spec.toolCallId()));
    }

    /** @param input 原始子智能体提示词，不包含模型生成的 taskId。 */
    public record ReplaySpec(String toolCallId, String subAgentId, String input) {
    }
}
