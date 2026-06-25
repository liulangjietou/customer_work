package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.memory.ContextMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Harness Agent 工厂（AgentScope 2.0 新增能力的统一装配入口）。
 *
 * <p>采用 {@code HarnessAgent.fromAgent(ReActAgent)} 的统一架构：复用
 * {@link CustomerServiceAgentFactory} 构建的内层 ReActAgent（已含模型 / 工具 / 长期记忆 / RAG /
 * Skill / Hook / 权限），再在其上叠加 Harness 专属能力——</p>
 * <ul>
 *   <li><b>Compaction</b>：长对话上下文自动压缩（替代 1.x AutoContextMemory）；</li>
 *   <li><b>Plan Mode</b>：只读规划期，先出 markdown 方案、获批后再写；</li>
 *   <li><b>Workspace / Sandbox</b>：文件工具与代码执行的隔离工作区；</li>
 *   <li><b>Subagent</b>：把订单 / 售后 / 知识库专家作为子智能体编排；</li>
 *   <li><b>Permission</b>：声明式工具授权（与内层 ReActAgent 共用 {@link PermissionContextState}）。</li>
 * </ul>
 *
 * <p>仅当 {@code customer-work.harness.enabled=true} 时按需使用；主链路默认仍走轻量
 * {@link CustomerServiceAgentFactory}，二者按业务流自由选择。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class HarnessAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentFactory.class);

    private final CustomerServiceAgentFactory agentFactory;
    private final ContextMemoryFactory contextMemoryFactory;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final AgentStateStore stateStore;
    private final PermissionContextState permissionContext;
    private final CustomerWorkProperties properties;

    public HarnessAgentFactory(CustomerServiceAgentFactory agentFactory,
                               ContextMemoryFactory contextMemoryFactory,
                               MultiAgentOrchestrator multiAgentOrchestrator,
                               AgentStateStore stateStore,
                               PermissionContextState permissionContext,
                               CustomerWorkProperties properties) {
        this.agentFactory = agentFactory;
        this.contextMemoryFactory = contextMemoryFactory;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.stateStore = stateStore;
        this.permissionContext = permissionContext;
        this.properties = properties;
    }

    /**
     * 为指定会话构建一个 HarnessAgent，叠加全部已启用的 2.0 Harness 能力。
     *
     * @param sessionId 会话标识（可含租户前缀如 tenantA:conv-1）
     */
    public HarnessAgent createHarnessAgent(String sessionId) {
        CustomerWorkProperties.Harness cfg = properties.getHarness();
        ReActAgent inner = agentFactory.createAgent(sessionId);

        HarnessAgent.Builder builder = HarnessAgent.Builder.fromAgent(inner)
            .stateStore(stateStore)
            .defaultSessionId(sessionId)
            .permissionContext(permissionContext)
            .workspace(resolveWorkspace(cfg.getWorkspaceDir()));

        // 上下文压缩（长对话有界）
        CompactionConfig compaction = contextMemoryFactory.createCompaction();
        if (compaction != null) {
            builder.compaction(compaction);
        }

        // Plan Mode（只读规划期）
        if (cfg.getPlanMode().isEnabled()) {
            builder.enablePlanMode().allowShellInPlanMode(cfg.getPlanMode().isAllowShell());
            log.info("[Harness] plan mode enabled (allowShell={})", cfg.getPlanMode().isAllowShell());
        }

        // 子智能体：把专家 Agent 注册为 HarnessAgent 的 subagent
        if (cfg.getSubagent().isEnabled()) {
            for (ReActAgent expert : multiAgentOrchestrator.buildSpecialists()) {
                builder.subagentFactory(expert.getName(), id -> expert);
            }
            log.info("[Harness] subagents registered: order/after-sales/knowledge experts");
        }

        log.info("[Harness] HarnessAgent built for session {}", sessionId);
        return builder.build();
    }

    /** 解析并创建工作区目录（沙箱 / 文件工具的隔离根）。 */
    private Path resolveWorkspace(String dir) {
        Path workspace = Path.of(dir == null || dir.isBlank() ? "./data/workspace" : dir);
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[Harness] create workspace dir failed, code={}", "WORKSPACE_INIT_ERROR", e);
        }
        return workspace;
    }
}
