package com.richard.fyoung.customeradmin.workspace.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentBackupModel;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSubAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSubAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.runtime.AdminMcpFactory;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover.FailoverModel;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover.ModelCircuitBreakerRegistry;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiAgentSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 动态智能体运行时工厂：按 {@code ai_agent} 任意一行现场组装 {@link Agent}
 * （不复用启动期一次性装配的 {@code CustomerServiceAgentFactory}，见实施计划"上下文"一节的构建时机差异）。
 *
 * <p>装配步骤：① 校验智能体已启用 → ② 查关联模型，经 {@link AdminModelFactory#buildModel} 现场构建
 * OpenAI 兼容 {@link Model} → ③ 查 {@code ai_agent_mcp} 关联行，逐个 {@link McpClientBuilder} 注册进
 * {@link Toolkit}（参考 {@code McpToolkitConfigurer} 的写法，改为读数据库行）→ ④ 查
 * {@code ai_agent_skill} 关联行，把 {@code content}（SKILL.md 正文）落盘后复用现成的
 * {@link FileSystemSkillRepository} 加载（不自造 Skill 解析逻辑）→ ⑤ {@link ReActAgent.Builder} 组装
 * （tasklist/metatool/maxIters/工具超时重试等内层能力与参数一并接线）→ ⑥ 若命中 {@link #requiresHarness}
 * （capabilities 含 vibecoding/plan/subagent/skill-learning/dynamic-subagent 任一，或配置了上下文压缩），用
 * {@link HarnessAgent.Builder#fromAgent} 在内层 ReActAgent 上按能力叠加沙箱/计划模式/技能自进化/
 * 上下文压缩/子智能体编排（workspace 限定到 {@code ./data/admin-workspace/{agentCode}}）。</p>
 *
 * <p>本类只负责"从零构建一次"，不做缓存——缓存由 {@link AgentInstanceCache} 负责。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminAgentInstanceFactory {

    private static final Logger log = LoggerFactory.getLogger(AdminAgentInstanceFactory.class);

    private static final String CAPABILITY_VIBECODING = "vibecoding";
    private static final String CAPABILITY_PLAN = "plan";
    private static final String CAPABILITY_SUBAGENT = "subagent";
    private static final String CAPABILITY_TASKLIST = "tasklist";
    private static final String CAPABILITY_SKILL_LEARNING = "skill-learning";
    private static final String CAPABILITY_DYNAMIC_SUBAGENT = "dynamic-subagent";
    private static final String CAPABILITY_DELIMITER = ",";
    private static final int STATUS_ENABLED = 1;
    private static final int DEFAULT_MAX_ITERS = 10;
    /** compress_trigger_msgs 已填但 compress_keep_msgs 未填时的保留消息数默认值。 */
    private static final int DEFAULT_COMPRESS_KEEP_MSGS = 10;
    /** Plan Mode 计划文件目录名（workspace 下的子目录）。 */
    private static final String PLAN_DIR_NAME = "plans";
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final String WORKSPACE_ROOT = "./data/admin-workspace";
    private static final String SKILL_ROOT = "./data/admin-skills";
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个乐于助人的智能助手。不清楚的问题说不知道，需要人工确认，不要瞎编或者胡说八道。";

    private final AiAgentMapper agentMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentBackupModelMapper agentBackupModelMapper;
    private final AiAgentSubAgentMapper agentSubAgentMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final AiMcpMapper mcpMapper;
    private final AiSkillMapper skillMapper;
    private final AiAgentSystemToolMapper agentSystemToolMapper;
    private final AiSystemToolMapper systemToolMapper;
    private final ApplicationContext applicationContext;
    private final AdminModelFactory modelFactory;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AgentStateStore stateStore;
    private final PermissionContextState permissionContext;
    private final AdminMcpFactory mcpFactory;
    private final AdminSandboxProperties sandboxProperties;
    private final SandboxGuardMiddleware sandboxGuardMiddleware;
    private final PlanConfirmationMiddleware planConfirmationMiddleware;
    private final ModelCircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * {@code agentCode -> ToolSourceInfo}：{@link #build} 每次重建都会覆盖写入，天然跟着
     * {@link AgentInstanceCache} 的重建节奏保持新鲜，不需要额外的失效联动。
     */
    private final ConcurrentHashMap<String, ToolSourceInfo> toolSourceCache = new ConcurrentHashMap<>();

    public AdminAgentInstanceFactory(AiAgentMapper agentMapper, AiAgentMcpMapper agentMcpMapper,
                                      AiAgentSkillMapper agentSkillMapper, AiAgentBackupModelMapper agentBackupModelMapper,
                                      AiAgentSubAgentMapper agentSubAgentMapper,
                                      AiModelConfigMapper modelConfigMapper,
                                      AiMcpMapper mcpMapper, AiSkillMapper skillMapper,
                                      AiAgentSystemToolMapper agentSystemToolMapper, AiSystemToolMapper systemToolMapper,
                                      ApplicationContext applicationContext,
                                      AdminModelFactory modelFactory, AesGcmCryptoUtil cryptoUtil,
                                      AgentStateStore stateStore, PermissionContextState permissionContext,
                                      AdminMcpFactory mcpFactory, AdminSandboxProperties sandboxProperties,
                                      SandboxGuardMiddleware sandboxGuardMiddleware,
                                      PlanConfirmationMiddleware planConfirmationMiddleware,
                                      ModelCircuitBreakerRegistry circuitBreakerRegistry) {
        this.agentMapper = agentMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.agentSubAgentMapper = agentSubAgentMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.mcpMapper = mcpMapper;
        this.skillMapper = skillMapper;
        this.agentSystemToolMapper = agentSystemToolMapper;
        this.systemToolMapper = systemToolMapper;
        this.applicationContext = applicationContext;
        this.modelFactory = modelFactory;
        this.cryptoUtil = cryptoUtil;
        this.stateStore = stateStore;
        this.permissionContext = permissionContext;
        this.mcpFactory = mcpFactory;
        this.sandboxProperties = sandboxProperties;
        this.sandboxGuardMiddleware = sandboxGuardMiddleware;
        this.planConfirmationMiddleware = planConfirmationMiddleware;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /** 查该智能体当前已装配的工具来源登记表；从未 {@link #build} 过（比如尚未触发过对话）时返回空表。 */
    public ToolSourceInfo toolSourceFor(String agentCode) {
        return toolSourceCache.getOrDefault(agentCode, ToolSourceInfo.EMPTY);
    }

    /** 单次调用的运行时上下文：{@code userId=agentCode} 天然隔离不同智能体共享同一 StateStore 时的状态。 */
    public RuntimeContext contextFor(String agentCode, String sessionId) {
        return RuntimeContext.builder()
            .userId(agentCode)
            .sessionId(StringUtils.hasText(sessionId) ? sessionId : "default")
            .build();
    }

    /**
     * 按智能体当前关联的模型配置现场构建一个独立 {@link Model} 实例，供"一次性调用"场景使用
     * （如 Git 助手的 diff 摘要/commit message/PR description 生成）——不经过 {@link #build}
     * 的 ReAct 工具循环组装，不带 workspace/toolkit，纯粹的模型直连，避免节外生枝触发文件工具。
     */
    public Model buildModelForAgent(String agentCode) {
        AiAgent agent = requireEnabledAgent(agentCode);
        return buildAgentModel(agent);
    }

    /** 从零构建一次智能体实例（供 {@link AgentInstanceCache} 惰性重建时调用）。 */
    public Agent build(String agentCode) {
        AiAgent agent = requireEnabledAgent(agentCode);
        List<String> capabilities = parseCapabilities(agent.getCapabilities());
        Model model = buildAgentModel(agent);
        ReActAgent inner = buildInnerReActAgent(agent, capabilities, model);

        if (!requiresHarness(capabilities, agent.getCompressTriggerMsgs())) {
            log.info("[workspace] agent built: agentCode={} capabilities={}", agentCode, capabilities);
            return inner;
        }
        // visited 记录子智能体构建链上的父链 agentCode，用于断开循环引用（A→B→A），根节点先入链
        Set<String> visited = new HashSet<>();
        visited.add(agentCode);
        return buildHarnessAgent(agent, capabilities, inner, model, visited);
    }

    /**
     * 是否需要用 {@link HarnessAgent.Builder#fromAgent} 把内层 ReActAgent 升级为 HarnessAgent：
     * capabilities 含 vibecoding/plan/subagent/skill-learning/dynamic-subagent 任一，或配置了上下文压缩触发阈值。
     * tasklist 是 {@link ReActAgent.Builder#enableTaskList(boolean)} 的内层能力，单独勾选不触发升级。
     * 抽成纯函数便于单测（不依赖任何注入的协作对象）。
     */
    static boolean requiresHarness(List<String> capabilities, Integer compressTriggerMsgs) {
        return capabilities.contains(CAPABILITY_VIBECODING)
            || capabilities.contains(CAPABILITY_PLAN)
            || capabilities.contains(CAPABILITY_SUBAGENT)
            || capabilities.contains(CAPABILITY_SKILL_LEARNING)
            || capabilities.contains(CAPABILITY_DYNAMIC_SUBAGENT)
            || compressTriggerMsgs != null;
    }

    /**
     * 组装内层 ReActAgent（模型/工具/技能/高级参数）。子智能体也走这条路径——子智能体只构建为内层
     * ReActAgent，不递归叠加它自己的 Harness 能力，避免嵌套复杂度。
     */
    private ReActAgent buildInnerReActAgent(AiAgent agent, List<String> capabilities, Model model) {
        String agentCode = agent.getAgentCode();
        Set<String> mcpToolNames = new HashSet<>();
        Toolkit toolkit = buildToolkit(agent.getId(), mcpToolNames);
        buildSystemTools(agent.getId(), toolkit);

        ReActAgent.Builder builder = ReActAgent.builder()
            .name("AdminAgent-" + agentCode)
            .sysPrompt(StringUtils.hasText(agent.getSystemPrompt()) ? agent.getSystemPrompt() : DEFAULT_SYSTEM_PROMPT)
            .model(model)
            .toolkit(toolkit)
            .stateStore(stateStore)
            .defaultSessionId(agentCode)
            .permissionContext(permissionContext)
            .maxIters(agent.getMaxIters() != null ? agent.getMaxIters() : DEFAULT_MAX_ITERS)
            // 中断后无缝续跑：会话被安全中断（见 ChatService#interrupt）后再次调用，先恢复被打断的
            // 挂起工具调用，再处理新消息，与 customer-work-spring-boot-starter 侧的默认行为保持一致。
            .enablePendingToolRecovery(true);
        if (capabilities.contains(CAPABILITY_VIBECODING)) {
            // 只有 vibecoding 能力的 agent 才会跑到文件系统/shell 工具，护栏只对这类 agent 挂载。
            // 顺序关键（first = outermost）：HITL 计划确认在外层先看到原始命令挂起询问，护栏在内层作最后防线——
            // 即便高风险被人工批准，catastrophic 命令仍会被护栏改写，护栏不被绕过（需求 §4.4.2.5「两者叠加」）。
            // bypass 模式下 HITL 中间件纯透传，行为等价于改造前。
            builder.middleware(planConfirmationMiddleware);
            builder.middleware(sandboxGuardMiddleware);
        }
        if (capabilities.contains(CAPABILITY_TASKLIST)) {
            // 任务列表：内层 ReActAgent 自带能力，不依赖 Harness
            builder.enableTaskList(true);
        }
        if (capabilities.contains(CAPABILITY_SKILL_LEARNING)) {
            // 互动学习新技能（内层半边）：MetaTool 允许 Agent 自主编排技能；Harness 半边见 buildHarnessAgent
            builder.enableMetaTool(true);
        }
        applyToolExecutionConfig(builder, agent);

        Set<String> skillToolNames = new HashSet<>();
        SkillBox skillBox = buildSkillBox(agent, toolkit, skillToolNames);
        if (skillBox != null) {
            builder.skillBox(skillBox);
        }
        toolSourceCache.put(agentCode, new ToolSourceInfo(skillToolNames, mcpToolNames));
        return builder.build();
    }

    /**
     * 工具执行超时/重试：至少一个参数非空时才设置 {@link ExecutionConfig}；未填的项保持 null，
     * 由框架的配置合并（ExecutionConfig#mergeConfigs）回落到默认值（超时5分钟/尝试1次）。
     */
    private void applyToolExecutionConfig(ReActAgent.Builder builder, AiAgent agent) {
        if (agent.getToolTimeoutSeconds() == null && agent.getToolMaxAttempts() == null) {
            return;
        }
        ExecutionConfig.Builder execBuilder = ExecutionConfig.builder();
        if (agent.getToolTimeoutSeconds() != null) {
            execBuilder.timeout(Duration.ofSeconds(agent.getToolTimeoutSeconds()));
        }
        if (agent.getToolMaxAttempts() != null) {
            execBuilder.maxAttempts(agent.getToolMaxAttempts());
        }
        builder.toolExecutionConfig(execBuilder.build());
    }

    /**
     * 在内层 ReActAgent 上叠加 Harness 能力：vibecoding 沙箱 / plan 计划模式 / skill-learning 技能自进化 /
     * 上下文压缩 / 子智能体编排。非 vibecoding 时不挂 filesystem 沙箱（SandboxSafeAgentStateStore 也只是
     * docker filesystem 的 sessionId 转义问题，同样不需要），workspace 仍复用 {@link #resolveWorkspace}。
     */
    private HarnessAgent buildHarnessAgent(AiAgent agent, List<String> capabilities, ReActAgent inner,
                                           Model model, Set<String> visited) {
        String agentCode = agent.getAgentCode();
        boolean vibecoding = capabilities.contains(CAPABILITY_VIBECODING);

        // Docker 模式下 HarnessAgent 内部的 SessionSandboxStateStore 会给沙箱状态槽位拼出带 "/"
        // 的 sessionId（IsolationScope 四种取值全部如此，框架侧硬编码），而 MysqlAgentStateStore
        // 拒绝接受含路径分隔符的 sessionId，两者组合必然抛异常——用 SandboxSafeAgentStateStore
        // 包一层转义规避，local 模式与未挂 filesystem 沙箱的升级路径不受影响（见该类 Javadoc）。
        AgentStateStore harnessStateStore = vibecoding && sandboxProperties.isDockerMode()
            ? new SandboxSafeAgentStateStore(stateStore) : stateStore;
        Path workspace = resolveWorkspace(agentCode);
        HarnessAgent.Builder harnessBuilder = HarnessAgent.Builder.fromAgent(inner)
            .stateStore(harnessStateStore)
            .defaultSessionId(agentCode)
            .permissionContext(permissionContext)
            // 框架 #1644 缓解：HarnessAgent 未显式设置 generateOptions 时 streamEvents() 会 NPE，
            // 这里保证非空即可，实际推理参数仍由内层 ReActAgent 的模型配置决定
            .generateOptions(GenerateOptions.builder().build())
            .workspace(workspace);

        if (vibecoding) {
            if (sandboxProperties.isDockerMode()) {
                harnessBuilder.filesystem(buildDockerFilesystemSpec());
            } else {
                harnessBuilder.filesystem(buildLocalFilesystemSpec());
            }
        }
        if (capabilities.contains(CAPABILITY_PLAN)) {
            // 计划模式：只读规划期禁 shell，计划文件持久化到 workspace/plans
            harnessBuilder.enablePlanMode()
                .allowShellInPlanMode(false)
                .planFileDirectory(workspace.resolve(PLAN_DIR_NAME).toString());
        }
        if (capabilities.contains(CAPABILITY_SKILL_LEARNING)) {
            // 互动学习新技能（Harness 半边）：技能管理工具 + SkillCurator 自动沉淀成功模式
            harnessBuilder.enableSkillManageTool(true)
                .enableSkillCurator(SkillCuratorConfig.defaults());
        }
        if (agent.getCompressTriggerMsgs() != null) {
            int keepMsgs = agent.getCompressKeepMsgs() != null
                ? agent.getCompressKeepMsgs() : DEFAULT_COMPRESS_KEEP_MSGS;
            harnessBuilder.compaction(CompactionConfig.builder()
                .triggerMessages(agent.getCompressTriggerMsgs())
                .keepMessages(keepMsgs)
                .model(model)
                .build());
        }
        if (capabilities.contains(CAPABILITY_SUBAGENT)) {
            registerSubagents(harnessBuilder, agent, visited);
        }
        if (capabilities.contains(CAPABILITY_DYNAMIC_SUBAGENT)) {
            // 动态子Agent：框架在 HarnessAgent 上默认开启（DynamicSubagentsMiddleware），勾选时保留默认，
            // 主 Agent 可在运行时按任务临时声明子 Agent（SubagentSpecGenerator 现场生成规格，无需预注册）
            log.info("[workspace] dynamic subagents enabled: agentCode={}", agentCode);
        } else {
            // 未勾选则显式关闭，避免框架默认行为让所有 Harness 智能体都悄悄具备运行时造 Agent 的能力
            harnessBuilder.disableDynamicSubagents();
        }

        HarnessAgent harnessAgent = harnessBuilder.build();
        log.info("[workspace] harness agent built: agentCode={} capabilities={} sandboxMode={}",
            agentCode, capabilities, vibecoding ? sandboxProperties.getMode() : "none");
        return harnessAgent;
    }

    /**
     * 读 {@code ai_agent_sub_agent} 关联行，把子智能体注册进 HarnessAgent。子智能体的实际构建放在
     * subagentFactory 的 lambda 里惰性执行——spawn 时才查库组装，天然拿到子智能体最新配置。
     * 注册期跳过已停用/已删除/循环引用的子智能体并记 error 日志（参照 MCP 注册失败的容错风格，
     * 不阻断父智能体装配）；lambda 捕获包含父链的 visited 集合。
     */
    private void registerSubagents(HarnessAgent.Builder harnessBuilder, AiAgent agent, Set<String> visited) {
        List<Long> subAgentIds = agentSubAgentMapper.selectList(
                new LambdaQueryWrapper<AiAgentSubAgent>().eq(AiAgentSubAgent::getAgentId, agent.getId()))
            .stream().map(AiAgentSubAgent::getSubAgentId).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(subAgentIds)) {
            return;
        }
        for (Long subAgentId : subAgentIds) {
            AiAgent sub = agentMapper.selectById(subAgentId);
            if (sub == null || sub.getStatus() == null || sub.getStatus() != STATUS_ENABLED) {
                // 子智能体已删除/已停用：跳过注册，不阻断父智能体装配
                log.error("[workspace] subagent unavailable (skip registration), code={}, parentAgentCode={}, subAgentId={}",
                    "SUBAGENT_UNAVAILABLE", agent.getAgentCode(), subAgentId);
                continue;
            }
            String subCode = sub.getAgentCode();
            if (visited.contains(subCode)) {
                // 循环引用（如 A→B→A 或自引用）：跳过并记日志，不抛异常
                log.error("[workspace] subagent circular reference detected (skip registration), code={}, parentAgentCode={}, subAgentCode={}",
                    "SUBAGENT_CIRCULAR_REF", agent.getAgentCode(), subCode);
                continue;
            }
            // lambda 捕获包含父链 + 当前子节点的 visited 快照；子智能体只构建内层 ReActAgent（无递归），
            // 该链主要用于防御未来引入递归构建时的死循环
            Set<String> chain = new HashSet<>(visited);
            chain.add(subCode);
            harnessBuilder.subagentFactory(subCode, id -> buildSubagentInner(subCode, chain));
            log.info("[workspace] subagent registered: parentAgentCode={} subAgentCode={}",
                agent.getAgentCode(), subCode);
        }
    }

    /**
     * 惰性构建子智能体（spawn 时执行）：只构建内层 ReActAgent，不叠加子智能体自己的 Harness 能力。
     * spawn 时子智能体已被停用/删除会抛 {@link BizException}，由框架按 spawn 失败向上反馈（fast-fail）。
     */
    private Agent buildSubagentInner(String subAgentCode, Set<String> visitedChain) {
        AiAgent sub = requireEnabledAgent(subAgentCode);
        List<String> capabilities = parseCapabilities(sub.getCapabilities());
        // 子智能体的模型同样走主备容错装配（子智能体也可配置备用模型）
        Model model = buildAgentModel(sub);
        log.info("[workspace] building subagent (lazy spawn): subAgentCode={} chain={}", subAgentCode, visitedChain);
        return buildInnerReActAgent(sub, capabilities, model);
    }

    /** {@code admin.sandbox.mode=local}（默认）：无隔离，Agent 的 shell/文件工具直接跑在宿主机进程内。 */
    private LocalFilesystemSpec buildLocalFilesystemSpec() {
        return new LocalFilesystemSpec()
            .executeTimeoutSeconds(sandboxProperties.getExecuteTimeoutSeconds())
            .isolationScope(IsolationScope.AGENT);
    }

    /**
     * {@code admin.sandbox.mode=docker}：容器级隔离，Agent 的 shell/文件工具跑在独立 Docker 容器内。
     * {@code agentscope-harness} 已内置该实现，与 {@link #buildLocalFilesystemSpec()} 是同一套
     * {@code HarnessAgent.Builder#filesystem(...)} 挂载点，本机/服务器需已安装并运行 Docker。
     */
    private SandboxFilesystemSpec buildDockerFilesystemSpec() {
        AdminSandboxProperties.Docker docker = sandboxProperties.getDocker();
        return new DockerFilesystemSpec()
            .image(docker.getImage())
            .memorySizeBytes(docker.getMemoryMb() * BYTES_PER_MB)
            .cpuCount(docker.getCpuCount())
            .network(docker.getNetwork())
            .isolationScope(IsolationScope.AGENT);
    }

    /**
     * VibeCoding 沙箱工作区路径（智能体根目录）：{@code ./data/admin-workspace/{agentCode}}。
     * 仅供快照根路径使用，Agent 运行时请使用 {@link #resolveSessionWorkspace(String, String)} 按会话隔离。
     */
    public Path resolveWorkspace(String agentCode) {
        Path workspace = Path.of(WORKSPACE_ROOT, agentCode);
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[workspace] create workspace dir failed, code={}, agentCode={}", "WORKSPACE_INIT_ERROR", agentCode, e);
        }
        return workspace;
    }

    /**
     * VibeCoding 沙箱工作区路径（会话级隔离）：{@code ./data/admin-workspace/{agentCode}/sessions/{sessionId}}。
     * HarnessAgent 的文件操作根目录，不同会话产出物物理隔离，互不污染。
     *
     * <p><b>安全约束（会话路径解析的全链路唯一防御点）</b>：本方法是 stream/files/file-content/rollback
     * 等所有会话级功能解析磁盘路径的公共入口，sessionId 由前端透传（正常值是 UUID v4），在此统一做
     * 路径穿越校验——含路径分隔符或 {@code ..} 的 sessionId 直接 fast fail，并对拼接结果 normalize 后
     * 强校验必须仍落在该 agent 的 {@code sessions/} 根目录内。否则恶意 sessionId（如
     * {@code ../其他会话ID} / 绝对路径）可越界访问他人会话甚至宿主机任意目录（rollback 链路会对目标
     * 目录执行破坏性的 git checkout + clean）。</p>
     */
    public Path resolveSessionWorkspace(String agentCode, String sessionId) {
        String safeSession = requireSafeSessionId(sessionId);
        Path sessionsRoot = Path.of(WORKSPACE_ROOT, agentCode, "sessions").normalize();
        Path workspace = sessionsRoot.resolve(safeSession).normalize();
        // 双保险：字符黑名单之外，normalize 后必须仍是 sessions 根目录的真子路径（防未预见的编码绕过）
        if (!workspace.startsWith(sessionsRoot) || workspace.equals(sessionsRoot)) {
            log.error("[workspace] session workspace path traversal blocked, code={}, agentCode={}, sessionId={}",
                "SESSION_PATH_TRAVERSAL", agentCode, safeSession);
            throw new BizException(ResultCode.PARAM_INVALID, "非法 sessionId：解析路径越界");
        }
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[workspace] create session workspace dir failed, code={}, agentCode={}, sessionId={}",
                "SESSION_WORKSPACE_INIT_ERROR", agentCode, safeSession, e);
        }
        return workspace;
    }

    /**
     * sessionId 合法性校验：空值回退 {@code default}；含 {@code /}、{@code \}、{@code ..} 任一直接拒绝——
     * 前端生成的 sessionId 是 UUID v4（十六进制 + 连字符，见 customer-admin-web/src/utils/uuid.ts），
     * 正常值不可能出现这些字符，出现即恶意构造，fast fail。
     */
    static String requireSafeSessionId(String sessionId) {
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        if (safeSession.contains("/") || safeSession.contains("\\") || safeSession.contains("..")) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法 sessionId：不允许包含路径分隔符或 ..");
        }
        return safeSession;
    }

    private AiAgent requireEnabledAgent(String agentCode) {
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, agentCode));
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + agentCode);
        }
        if (agent.getStatus() == null || agent.getStatus() != STATUS_ENABLED) {
            throw new BizException(ResultCode.AGENT_DISABLED, "智能体未启用: " + agentCode);
        }
        return agent;
    }

    private Model buildModel(Long modelId) {
        AiModelConfig modelConfig = modelConfigMapper.selectById(modelId);
        if (modelConfig == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体关联的模型配置不存在: " + modelId);
        }
        String apiKey = cryptoUtil.decrypt(modelConfig.getApiKey());
        return modelFactory.buildModel(modelConfig.getProvider(), modelConfig.getBaseUrl(), apiKey, modelConfig.getModel());
    }

    /**
     * 按智能体主备模型配置构建运行时 {@link Model}：无备用模型时行为与旧逻辑完全一致（直接返回单个主模型，
     * 不包 {@link FailoverModel}）；有备用模型时按 {@code sort_order} 组装有序候选（主在前备在后）包成
     * {@link FailoverModel}，运行时主模型失败自动按序切备并带熔断记忆。
     */
    private Model buildAgentModel(AiAgent agent) {
        Model primaryModel = buildModel(agent.getModelId());
        List<Long> backupModelIds = agentBackupModelMapper.selectList(
                new LambdaQueryWrapper<AiAgentBackupModel>()
                    .eq(AiAgentBackupModel::getAgentId, agent.getId())
                    .orderByAsc(AiAgentBackupModel::getSortOrder))
            .stream().map(AiAgentBackupModel::getModelId).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(backupModelIds)) {
            return primaryModel;
        }
        List<FailoverModel.Candidate> candidates = new ArrayList<>();
        candidates.add(new FailoverModel.Candidate(agent.getModelId(), primaryModel));
        for (Long backupModelId : backupModelIds) {
            candidates.add(new FailoverModel.Candidate(backupModelId, buildModel(backupModelId)));
        }
        log.info("[workspace] failover model built: agentCode={} candidates={}", agent.getAgentCode(), candidates.size());
        return new FailoverModel(candidates, circuitBreakerRegistry);
    }

    /**
     * MCP 握手（{@code registerMcpClient} 内部先 {@code initialize()} 再 {@code listTools()}）的硬超时。
     * 必须显式给 {@code .block(...)} 传超时——不传时 {@code Mono.block()} 会无限等待，一旦某个 MCP
     * 服务握手卡住（比如服务端不支持可选的 SSE 长连接导致 SDK 内部状态卡死，见批次六联调排查），
     * 整个 {@code /chat/stream} 请求线程会跟着永久挂起、前端界面看起来"没有响应"，且不会抛出任何
     * 异常——下面的 try/catch 根本等不到超时异常被抛出。
     */
    private static final java.time.Duration MCP_REGISTER_TIMEOUT = java.time.Duration.ofSeconds(10);

    /**
     * 读 {@code ai_agent_mcp} 关联行，逐个动态注册进 Toolkit（参考 McpToolkitConfigurer 的写法）。
     * {@code mcpToolNames} 收集本次注册进来的工具名——{@code ToolUseBlock} 不带来源信息，只能靠
     * 注册前后 {@link Toolkit#getToolNames()} 的差集在装配时记下来，供对话流式展示按来源分类。
     */
    private Toolkit buildToolkit(Long agentId, Set<String> mcpToolNames) {
        Toolkit toolkit = new Toolkit();
        List<Long> mcpIds = agentMcpMapper.selectList(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, agentId))
            .stream().map(AiAgentMcp::getMcpId).collect(Collectors.toList());
        if (mcpIds.isEmpty()) {
            return toolkit;
        }
        for (AiMcp mcp : mcpMapper.selectBatchIds(mcpIds)) {
            try {
                Set<String> before = new HashSet<>(toolkit.getToolNames());
                McpClientWrapper wrapper = buildMcpClient(mcp).block(MCP_REGISTER_TIMEOUT);
                if (wrapper != null) {
                    toolkit.registerMcpClient(wrapper).block(MCP_REGISTER_TIMEOUT);
                    Set<String> added = new HashSet<>(toolkit.getToolNames());
                    added.removeAll(before);
                    mcpToolNames.addAll(added);
                    log.info("[workspace] MCP registered: name={} type={}", mcp.getMcpName(), mcp.getMcpType());
                }
            } catch (Exception e) {
                // 单个 MCP 不可用（含握手超时）不应阻断整个智能体的装配，跳过它继续装配其余能力
                log.error("[workspace] MCP registration failed, code={}, name={}", "MCP_REGISTER_FAIL", mcp.getMcpName(), e);
            }
        }
        return toolkit;
    }

    private reactor.core.publisher.Mono<McpClientWrapper> buildMcpClient(AiMcp mcp) throws Exception {
        return mcpFactory.buildClientBuilder(mcp.getMcpName(), mcp.getMcpType(), mcp.getConfig())
            .timeout(java.time.Duration.ofSeconds(30))
            .buildAsync();
    }

    /**
     * 读 {@code ai_agent_system_tool} 关联行，按 {@code tool_code}（= Spring Bean 名）取工具 Bean 注册进 Toolkit。
     * 只注册 {@code enabled=1} 的工具；某个 tool_code 取不到 Bean（比如种子行存在但代码没实现对应类）不抛异常、
     * {@code log.error} 记录后跳过，避免一个坏配置拖垮整个智能体装配。
     */
    private void buildSystemTools(Long agentId, Toolkit toolkit) {
        List<Long> toolIds = agentSystemToolMapper.selectList(
                new LambdaQueryWrapper<AiAgentSystemTool>().eq(AiAgentSystemTool::getAgentId, agentId))
            .stream().map(AiAgentSystemTool::getSystemToolId).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(toolIds)) {
            return;
        }
        for (AiSystemTool tool : systemToolMapper.selectBatchIds(toolIds)) {
            if (tool.getEnabled() == null || tool.getEnabled() != STATUS_ENABLED) {
                continue;
            }
            try {
                Object bean = applicationContext.getBean(tool.getToolCode());
                toolkit.registerTool(bean);
                log.info("[workspace] system tool registered: code={}", tool.getToolCode());
            } catch (Exception e) {
                log.error("system tool bean not found, code={}, toolCode={}", "SYSTOOL-BEAN-MISSING", tool.getToolCode(), e);
            }
        }
    }

    /**
     * 读 {@code ai_agent_skill} 关联行，把 content（SKILL.md 正文）落盘后复用 FileSystemSkillRepository 加载。
     * {@code skillToolNames} 收集本次注册进来的工具名，同 {@link #buildToolkit} 的差集手法。
     */
    private SkillBox buildSkillBox(AiAgent agent, Toolkit toolkit, Set<String> skillToolNames) {
        List<Long> skillIds = agentSkillMapper.selectList(
                new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, agent.getId()))
            .stream().map(AiAgentSkill::getSkillId).collect(Collectors.toList());
        if (skillIds.isEmpty()) {
            return null;
        }
        try {
            Path skillDir = Path.of(SKILL_ROOT, agent.getAgentCode());
            Files.createDirectories(skillDir);
            for (AiSkill skill : skillMapper.selectBatchIds(skillIds)) {
                Path skillSubDir = skillDir.resolve(skill.getSkillCode());
                Files.createDirectories(skillSubDir);
                Files.writeString(skillSubDir.resolve("SKILL.md"), skill.getContent());
            }
            List<AgentSkill> skills = new FileSystemSkillRepository(skillDir, false).getAllSkills();
            SkillBox skillBox = new SkillBox(toolkit);
            Set<String> before = new HashSet<>(toolkit.getToolNames());
            for (AgentSkill skill : skills) {
                skillBox.registerSkill(skill);
            }
            Set<String> added = new HashSet<>(toolkit.getToolNames());
            added.removeAll(before);
            skillToolNames.addAll(added);
            log.info("[workspace] skills loaded: agentCode={} count={}", agent.getAgentCode(), skills.size());
            return skillBox;
        } catch (Exception e) {
            log.error("[workspace] skill loading failed (skip skill wiring), code={}, agentCode={}",
                "SKILL_LOAD_ERROR", agent.getAgentCode(), e);
            return null;
        }
    }

    private List<String> parseCapabilities(String capabilities) {
        return StringUtils.hasText(capabilities)
            ? Arrays.asList(capabilities.split(CAPABILITY_DELIMITER)) : List.of();
    }
}
