package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;
import com.richard.fyoung.customerwork.core.memory.ContextMemoryFactory;
import com.richard.fyoung.customerwork.core.memory.HarnessMemorySyncService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import com.richard.fyoung.customerwork.infra.config.properties.HarnessProperties;

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
    private final Model model;
    private final CustomerWorkProperties properties;
    private final HarnessMemorySyncService memorySyncService;

    public HarnessAgentFactory(CustomerServiceAgentFactory agentFactory,
                               ContextMemoryFactory contextMemoryFactory,
                               MultiAgentOrchestrator multiAgentOrchestrator,
                               AgentStateStore stateStore,
                               PermissionContextState permissionContext,
                               Model model,
                               CustomerWorkProperties properties,
                               HarnessMemorySyncService memorySyncService) {
        this.agentFactory = agentFactory;
        this.contextMemoryFactory = contextMemoryFactory;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.stateStore = stateStore;
        this.permissionContext = permissionContext;
        this.model = model;
        this.properties = properties;
        this.memorySyncService = memorySyncService;
    }

    /**
     * 为指定会话构建一个 HarnessAgent，叠加全部已启用的 2.0 Harness 能力。
     *
     * @param sessionId 会话标识（可含租户前缀如 tenantA:conv-1）
     */
    public HarnessAgent createHarnessAgent(String sessionId) {
        HarnessProperties cfg = properties.getHarness();
        ReActAgent inner = agentFactory.createAgent(sessionId);

        HarnessAgent.Builder builder = HarnessAgent.Builder.fromAgent(inner)
            .stateStore(stateStore)
            .defaultSessionId(sessionId)
            .permissionContext(permissionContext)
            // 框架 #1644：HarnessAgent 未显式设置 generateOptions 时，streamEvents() 会因空的
            // generateOptions 触发 NPE。此处显式设置一份保守默认，规避该缺陷；具体模型参数仍以
            // 内层 ReActAgent 的模型配置为准，这里只保证 generateOptions 非空。
            .generateOptions(defaultGenerateOptions())
            .workspace(resolveWorkspace(cfg.getWorkspaceDir()));

        // 上下文压缩（长对话有界）
        CompactionConfig compaction = contextMemoryFactory.createCompaction();
        boolean filesystemEnabled = !"none".equalsIgnoreCase(cfg.getSandbox().getMode());
        HarnessOptInPolicy.apply(builder,
            filesystemEnabled,
            compaction != null,
            cfg.isToolResultEvictionEnabled(),
            cfg.getSubagent().isEnabled(),
            false,
            cfg.isSkillCuratorEnabled());
        if (compaction != null) {
            builder.compaction(compaction);
        }

        // 分层记忆：MEMORY.md 持久画像 + 会话沉淀 + 自动 consolidation
        // 框架只从 workspace 文件读记忆，故构建前先把 MySQL 里的权威副本水合下来——
        // 否则换机 / 重启 / 清理 workspace 之后，历史记忆对框架就等于不存在。
        boolean memoryEnabled = cfg.isMemoryEnabled();
        if (memoryEnabled) {
            memorySyncService.hydrate(resolveWorkspace(cfg.getWorkspaceDir()));
            log.info("[Harness] layered memory enabled (MEMORY.md + consolidation)");
        }
        HarnessMemoryPolicy.apply(builder, memoryEnabled, model);

        // 环境级记忆：跨会话共享的环境记忆
        if (StringUtils.hasText(cfg.getEnvironmentMemory())) {
            builder.environmentMemory(cfg.getEnvironmentMemory());
            log.info("[Harness] environment memory enabled: {}", cfg.getEnvironmentMemory());
        }

        // 超大工具结果落盘：上下文只留占位符与预览，原文落盘到工作区
        if (cfg.isToolResultEvictionEnabled()) {
            builder.toolResultEviction(ToolResultEvictionConfig.defaults());
            log.info("[Harness] tool-result eviction enabled");
        }

        // 技能自进化：SkillCurator + 技能管理/晋升工具（成功模式沉淀为可复用技能）
        if (cfg.isSkillCuratorEnabled()) {
            builder.enableSkillManageTool(true)
                .enableSkillCurator(SkillCuratorConfig.defaults());
            log.info("[Harness] skill curator + manage tool enabled (self-evolution)");
        }

        // 额外上下文文件（人格 / 领域知识，磁盘 Markdown 每轮注入 system prompt）
        if (StringUtils.hasText(cfg.getAdditionalContextFile())) {
            builder.additionalContextFile(cfg.getAdditionalContextFile());
            log.info("[Harness] additional context file: {}", cfg.getAdditionalContextFile());
        }

        // Plan Mode（只读规划期）：计划文件持久化到 workspace/plans
        if (cfg.getPlanMode().isEnabled()) {
            builder.enablePlanMode()
                .allowShellInPlanMode(cfg.getPlanMode().isAllowShell())
                .planFileDirectory(resolveWorkspace(cfg.getWorkspaceDir()).resolve("plans").toString());
            log.info("[Harness] plan mode enabled (allowShell={}, plans dir=workspace/plans)",
                cfg.getPlanMode().isAllowShell());
        }

        // 安全沙箱执行：把工具/代码执行限定在隔离环境（Local 子进程 / Docker 容器），快照可跨进程恢复
        applySandbox(builder, cfg.getSandbox());

        // 子智能体：把专家 Agent 注册为 HarnessAgent 的 subagent
        if (cfg.getSubagent().isEnabled()) {
            for (ReActAgent expert : multiAgentOrchestrator.buildSpecialists()) {
                builder.subagentFactory(expert.getName(), id -> expert);
            }
            log.info("[Harness] subagents registered: order/after-sales/knowledge experts");
        }

        HarnessAgent harnessAgent = builder.build();
        HarnessOptInPolicy.pruneBuiltInTools(harnessAgent, cfg.getSubagent().isEnabled(), false);
        log.info("[Harness] HarnessAgent built for session {}", sessionId);
        return harnessAgent;
    }

    /**
     * 对话轮次结束后把分层记忆回写权威存储（MySQL）。
     *
     * <p>框架只往 workspace 文件里写记忆，没有"记忆已更新"的回调，故回写时机只能由调用方在轮次结束后
     * 显式触发——{@code streamEvents()} 完成、或 {@code doFinally} 里调一次即可。不调不会报错，
     * 但本次对话沉淀的记忆就只留在容器本地磁盘上，实例销毁即丢。</p>
     *
     * <p>{@code harness.memory-enabled=false} 时无记忆可回写，直接跳过。</p>
     */
    public void persistMemory() {
        HarnessProperties cfg = properties.getHarness();
        if (!cfg.isMemoryEnabled()) {
            return;
        }
        memorySyncService.persistIfChanged(resolveWorkspace(cfg.getWorkspaceDir()));
    }

    /**
     * 默认 generateOptions（框架 #1644 缓解）：仅用于保证 HarnessAgent 的 generateOptions 非空，
     * 避免 streamEvents() NPE。不覆盖任何具体模型参数（温度 / maxTokens 等），实际推理参数仍由
     * 内层 ReActAgent 的模型配置决定。
     */
    private GenerateOptions defaultGenerateOptions() {
        return GenerateOptions.builder().build();
    }

    /** 按配置选择并应用沙箱文件系统（none 时不隔离）。 */
    private void applySandbox(HarnessAgent.Builder builder, HarnessProperties.Sandbox cfg) {
        String mode = cfg.getMode() == null ? "none" : cfg.getMode().trim().toLowerCase();
        IsolationScope scope = resolveIsolation(cfg.getIsolationScope());
        switch (mode) {
            case "local": {
                LocalFilesystemSpec spec = new LocalFilesystemSpec()
                    .executeTimeoutSeconds(cfg.getExecuteTimeoutSeconds())
                    .isolationScope(scope);
                builder.filesystem(spec);
                log.info("[Harness] sandbox=local (timeout={}s, scope={})",
                    cfg.getExecuteTimeoutSeconds(), scope);
                break;
            }
            case "docker": {
                SandboxFilesystemSpec spec = new DockerFilesystemSpec()
                    .image(cfg.getImage())
                    .isolationScope(scope);
                builder.filesystem(spec);
                log.info("[Harness] sandbox=docker (image={}, scope={})", cfg.getImage(), scope);
                break;
            }
            default:
                // none：不隔离，沿用工作区目录
        }
    }

    private IsolationScope resolveIsolation(String scope) {
        if (scope == null) {
            return IsolationScope.SESSION;
        }
        switch (scope.trim().toLowerCase()) {
            case "user":
                return IsolationScope.USER;
            case "agent":
                return IsolationScope.AGENT;
            case "global":
                return IsolationScope.GLOBAL;
            default:
                return IsolationScope.SESSION;
        }
    }

    /** 解析并创建工作区目录（沙箱 / 文件工具的隔离根）。 */
    private Path resolveWorkspace(String dir) {
        Path workspace = Path.of(dir == null || dir.isBlank() ? RuntimeWorkDir.of("workspace") : dir);
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[Harness] create workspace dir failed, code={}", "WORKSPACE_INIT_ERROR", e);
        }
        return workspace;
    }
}
