package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;
import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 2.0 Harness 新能力配置。
 *
 * <p>{@code harness.enabled=true} 时，{@code HarnessAgentFactory} 在内层 ReActAgent 之上
 * 叠加 Harness 能力（{@code HarnessAgent.fromAgent(...)}）：权限系统、Plan Mode、上下文压缩、
 * 工作区沙箱、子智能体编排。Permission 始终对主 Agent 生效（ReActAgent 原生支持）。</p>
 */
@Data
public class HarnessProperties {
    /** 是否启用 HarnessAgent 包装（叠加 Plan Mode / Compaction / Subagent / Workspace）。默认关闭。 */
    private boolean enabled = false;
    /** 工作区 / 沙箱根目录（文件工具、代码执行、子智能体的隔离工作区）。 */
    private String workspaceDir = RuntimeWorkDir.of("workspace");
    /** 分层记忆：启用 MEMORY.md 持久画像 + 会话沉淀 + 自动 consolidation（MemoryConfig）。 */
    private boolean memoryEnabled = false;
    /**
     * 分层记忆的权威存储后端：jdbc（默认，落 cw_harness_memory 表）| memory（进程内）。
     *
     * <p>框架只认 {@code {workspace}/MEMORY.md} 文件，故落盘不可避免；本项决定"权威副本"存哪儿——
     * 默认 MySQL，workspace 里那份退化为可随时重建的工作副本（见 {@code HarnessMemorySyncService}）。</p>
     */
    private String memoryStoreMode = "jdbc";
    /** 环境级记忆（environmentMemory）：跨会话共享的环境记忆标识/文件，留空则不启用。 */
    private String environmentMemory = "";
    /** 超大工具结果落盘（ToolResultEviction）：把超长工具结果落盘、上下文只留占位符与预览。 */
    private boolean toolResultEvictionEnabled = false;
    /** 技能自进化：启用 SkillCurator + 技能管理/晋升工具（成功模式沉淀为可复用技能）。 */
    private boolean skillCuratorEnabled = false;
    /** 额外上下文文件（人格 / 领域知识等，磁盘 Markdown，每轮注入 system prompt），留空则不注入。 */
    private String additionalContextFile = "";
    /** 组织维度（org）：写入 RuntimeContext KV 命名空间，实现 session/user/org 多维隔离。留空则不写。 */
    private String org = "";
    /** 权限系统配置。 */
    private final Permission permission = new Permission();
    /** Plan Mode（只读规划期）配置。 */
    private final PlanMode planMode = new PlanMode();
    /** 子智能体编排配置。 */
    private final Subagent subagent = new Subagent();
    /** 安全沙箱执行配置。 */
    private final Sandbox sandbox = new Sandbox();

    /**
     * 安全沙箱执行：把工具执行 / 代码执行限定在隔离环境内，快照状态可跨进程恢复。
     *
     * <p>Local 与 Docker 沙箱内置于 Harness（无需额外依赖）；Kubernetes / e2b / daytona / AgentRun
     * 远端沙箱需引入对应 {@code agentscope-extensions-sandbox-*}（见 docs/MIGRATION-2.0.md）。</p>
     */
    @Data
    public static class Sandbox {
        /** 沙箱模式：none（不隔离，默认）| local（本地子进程）| docker（容器隔离）。 */
        private String mode = "none";
        /** 隔离粒度：session | user | agent | global。 */
        private String isolationScope = "session";
        /** docker 模式镜像。 */
        private String image = "python:3.11-slim";
        /** local 模式工具/代码执行超时秒数。 */
        private int executeTimeoutSeconds = 60;
    }

    /** 权限系统：控制工具 / 写操作的授权策略。 */
    @Data
    public static class Permission {
        /** 是否启用权限系统（对主 Agent 注入 PermissionContextState）。默认关闭。 */
        private boolean enabled = false;
        /** 权限模式：default | acceptEdits | explore | bypass | dontAsk。 */
        private String mode = "default";
        /** 命中以下工具名时进入"询问/人工确认"（ask 规则）。 */
        private List<String> askTools = new ArrayList<>(List.of("submitRefund", "transferToHuman"));
        /** 命中以下工具名时直接拒绝（deny 规则）。 */
        private List<String> denyTools = new ArrayList<>();
    }

    /** Plan Mode：先只读规划、获批后再写。 */
    @Data
    public static class PlanMode {
        private boolean enabled = false;
        /** Plan Mode 下是否允许执行 Shell。 */
        private boolean allowShell = false;
    }

    /** 子智能体编排（把订单/售后/知识库专家作为 HarnessAgent 的 subagent）。 */
    @Data
    public static class Subagent {
        private boolean enabled = false;
    }
}
