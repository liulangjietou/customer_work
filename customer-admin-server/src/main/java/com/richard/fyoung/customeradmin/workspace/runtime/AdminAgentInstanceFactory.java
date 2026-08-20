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
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.config.AdminKnowledgeGapRecorder;
import com.richard.fyoung.customeradmin.common.constant.AgentCapabilities;
import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.PromptInjectionGuardMiddleware;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime.KnowledgeRetrievalMiddleware;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime.KnowledgeRetrievalService;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.runtime.AdminMcpFactory;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover.FailoverModel;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover.ModelCircuitBreakerRegistry;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillFileMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiAgentSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySyncService;
import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;
import com.richard.fyoung.customerwork.data.calllog.AgentCallTimingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.IndirectInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SensitiveWordMiddleware;
import com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry;
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
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * 上下文压缩/子智能体编排（workspace 限定到系统临时目录下的 {@code admin-workspace/{agentCode}}）。</p>
 *
 * <p>本类只负责"从零构建一次"，不做缓存——缓存由 {@link AgentInstanceCache} 负责。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminAgentInstanceFactory {


    private static final Logger log = LoggerFactory.getLogger(AdminAgentInstanceFactory.class);
    private static final int DEFAULT_MAX_ITERS = 10;
    /** compress_trigger_msgs 已填但 compress_keep_msgs 未填时的保留消息数默认值。 */
    private static final int DEFAULT_COMPRESS_KEEP_MSGS = 10;
    /** Plan Mode 计划文件目录名（workspace 下的子目录）。 */
    private static final String PLAN_DIR_NAME = "plans";
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final String WORKSPACE_ROOT = RuntimeWorkDir.of("admin-workspace");
    private static final String SKILL_ROOT = RuntimeWorkDir.of("admin-skills");
    /** 会话产物目录名（{@code {agentCode}/sessions/}），docker 模式下 bind mount 前置预建的子目录之一。 */
    private static final String SESSIONS_DIR_NAME = "sessions";
    /** 容器工作区根（框架 {@code DockerSandboxClientOptions} 默认值，勿改——bind mount 目标路径依赖它）。 */
    static final String CONTAINER_WORKSPACE_ROOT = "/workspace";
    /** 探测宿主机 uid/gid 的 {@code id} 命令超时秒数。 */
    private static final long ID_CMD_TIMEOUT_SECONDS = 5;
    /**
     * 宿主机 JVM 进程的 {@code uid:gid}（类加载时经 {@code id -u}/{@code id -g} 探测一次，失败为 null）。
     * docker 模式下注入 {@code docker run --user uid:gid}：原生 Linux Docker 上容器默认以 root 运行，
     * bind mount 写出的文件在宿主机归 root:root，后续宿主机侧回滚（git checkout/clean）、文件保存会因
     * 属主不可写而失败；令容器进程 uid 与宿主机 JVM 一致后产物属主天然对齐。macOS Docker Desktop 本有
     * 属主映射，注入同样无害。探测失败（如 Windows）时不注入并记日志，回退行为见部署手册"Docker 模式产物同步"。
     */
    private static final String HOST_UID_GID = resolveHostUidGid();
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个乐于助人的智能助手。不清楚的问题说不知道，需要人工确认，不要瞎编或者胡说八道。";

    private final AiAgentMapper agentMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentBackupModelMapper agentBackupModelMapper;
    private final AiAgentSubAgentMapper agentSubAgentMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final AiMcpMapper mcpMapper;
    private final AiSkillMapper skillMapper;
    private final AiSkillFileMapper skillFileMapper;
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
    private final ExecutionModeMiddleware executionModeMiddleware;
    private final ModelCircuitBreakerRegistry circuitBreakerRegistry;
    private final AgentMemorySyncService memorySyncService;
    /** 分段耗时采集中间件（starter 提供，admin 显式装配），挂在内层 ReActAgent 上采集每次调用的分段耗时。 */
    private final AgentCallTimingMiddleware agentCallTimingMiddleware;
    /** 工具名→类别登记表：装配 MCP/Skill 时把工具名登记进来，采集时 onActing 据此归 MCP/SKILL（否则默认 TOOL）。 */
    private final ToolKindRegistry agentCallToolKindRegistry;
    /**
     * OTel 链路追踪中间件（框架自带，admin 侧由 {@code AdminOtelTracingConfig} 显式装配）：
     * 仅 {@code admin.observability.otel.enabled=true} 时容器里才有，否则为 null，本工厂跳过挂载。
     */
    private final OtelTracingMiddleware otelTracingMiddleware;
    /** 知识库检索服务：供每个智能体各挂一份 {@link KnowledgeRetrievalMiddleware} 做每轮召回注入。 */
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    /** 知识盲区埋点：中间件路径的未命中记进客服端库，与工具路径落同一张表。 */
    private final AdminKnowledgeGapRecorder knowledgeGapRecorder;
    /** 出站脱敏：与客服端同一实现、同一套规则。 */
    private final MaskingMiddleware maskingMiddleware;
    /** 直接提示词注入防护：拦用户输入里的越狱话术（间接注入由 indirectInjectionGuardMiddleware 负责）。 */
    private final PromptInjectionGuardMiddleware promptInjectionGuardMiddleware;
    /**
     * 敏感词过滤中间件；仅 {@code admin.content-guard.agent-filter-enabled=true} 时容器里才有，
     * 否则为 null，本工厂跳过挂载（后台仍可管理词库，只是 admin 自身对话不参与拦截）。
     */
    private final SensitiveWordMiddleware sensitiveWordMiddleware;
    /** 间接注入防护中间件（工具/MCP 结果隔离标记 + 检测告警），共享单例，见 {@code AdminAgentRuntimeConfig}。 */
    private final IndirectInjectionGuardMiddleware indirectInjectionGuardMiddleware;
    /** 后台委派任务仓储（落 MySQL），挂到每个 HarnessAgent 上接管 {@code agent_spawn} 的异步任务。 */
    private final TaskRepository taskRepository;
    /** VibeCoding 会话工作区持久化；未启用时为 null（产出物仅存本地临时目录）。 */
    private final SessionWorkspaceStorage sessionWorkspaceStorage;

    /**
     * {@code agentCode -> ToolSourceInfo}：{@link #build} 每次重建都会覆盖写入，天然跟着
     * {@link AgentInstanceCache} 的重建节奏保持新鲜，不需要额外的失效联动。
     */
    private final ConcurrentHashMap<String, ToolSourceInfo> toolSourceCache = new ConcurrentHashMap<>();

    public AdminAgentInstanceFactory(AiAgentMapper agentMapper, AiAgentMcpMapper agentMcpMapper,
                                      AiAgentSkillMapper agentSkillMapper, AiAgentBackupModelMapper agentBackupModelMapper,
                                      AiAgentSubAgentMapper agentSubAgentMapper,
                                      AiModelConfigMapper modelConfigMapper,
                                      AiMcpMapper mcpMapper, AiSkillMapper skillMapper, AiSkillFileMapper skillFileMapper,
                                      AiAgentSystemToolMapper agentSystemToolMapper, AiSystemToolMapper systemToolMapper,
                                      ApplicationContext applicationContext,
                                      AdminModelFactory modelFactory, AesGcmCryptoUtil cryptoUtil,
                                      AgentStateStore stateStore, PermissionContextState permissionContext,
                                      AdminMcpFactory mcpFactory, AdminSandboxProperties sandboxProperties,
                                      SandboxGuardMiddleware sandboxGuardMiddleware,
                                      ExecutionModeMiddleware executionModeMiddleware,
                                      ModelCircuitBreakerRegistry circuitBreakerRegistry,
                                      AgentMemorySyncService memorySyncService,
                                      AgentCallTimingMiddleware agentCallTimingMiddleware,
                                      ToolKindRegistry agentCallToolKindRegistry,
                                      ObjectProvider<OtelTracingMiddleware> otelTracingMiddlewareProvider,
                                      KnowledgeRetrievalService knowledgeRetrievalService,
                                      AdminKnowledgeGapRecorder knowledgeGapRecorder,
                                      MaskingMiddleware maskingMiddleware,
                                      PromptInjectionGuardMiddleware promptInjectionGuardMiddleware,
                                      ObjectProvider<SensitiveWordMiddleware> sensitiveWordMiddlewareProvider,
                                      IndirectInjectionGuardMiddleware indirectInjectionGuardMiddleware,
                                      TaskRepository taskRepository,
                                      ObjectProvider<SessionWorkspaceStorage> sessionWorkspaceStorageProvider) {
        this.indirectInjectionGuardMiddleware = indirectInjectionGuardMiddleware;
        this.taskRepository = taskRepository;
        // 容错 null provider：单测直传 null 依赖构造本类验证路径防御逻辑（同 CustomerServiceAgentFactory 的 meterRegistry 手法）
        this.sessionWorkspaceStorage = sessionWorkspaceStorageProvider == null
            ? null : sessionWorkspaceStorageProvider.getIfAvailable();
        this.agentMapper = agentMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.agentSubAgentMapper = agentSubAgentMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.mcpMapper = mcpMapper;
        this.skillMapper = skillMapper;
        this.skillFileMapper = skillFileMapper;
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
        this.executionModeMiddleware = executionModeMiddleware;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.memorySyncService = memorySyncService;
        this.agentCallTimingMiddleware = agentCallTimingMiddleware;
        this.agentCallToolKindRegistry = agentCallToolKindRegistry;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeGapRecorder = knowledgeGapRecorder;
        this.maskingMiddleware = maskingMiddleware;
        this.promptInjectionGuardMiddleware = promptInjectionGuardMiddleware;
        // provider 可为 null：路径解析类单测直接 new 本工厂并传 null 依赖，容器里则恒有 provider
        this.sensitiveWordMiddleware = sensitiveWordMiddlewareProvider == null
            ? null : sensitiveWordMiddlewareProvider.getIfAvailable();
        this.otelTracingMiddleware = otelTracingMiddlewareProvider == null
            ? null : otelTracingMiddlewareProvider.getIfAvailable();
    }

    /** 查该智能体当前已装配的工具来源登记表；从未 {@link #build} 过（比如尚未触发过对话）时返回空表。 */
    public ToolSourceInfo toolSourceFor(String agentCode) {
        return toolSourceCache.getOrDefault(WorkspaceRuntimeScope.agent(agentCode), ToolSourceInfo.EMPTY);
    }

    /** 单次调用上下文：userId 同时包含租户与智能体作用域，避免框架状态表跨租户碰撞。 */
    public RuntimeContext contextFor(String agentCode, String sessionId) {
        return RuntimeContext.builder()
            .userId(WorkspaceRuntimeScope.agent(agentCode))
            .sessionId(WorkspaceRuntimeScope.safeSession(sessionId))
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
     * capabilities 含 vibecoding/plan/subagent/skill-learning/dynamic-subagent/memory 任一，或配置了上下文压缩触发阈值。
     * tasklist 是 {@link ReActAgent.Builder#enableTaskList(boolean)} 的内层能力，单独勾选不触发升级。
     * 抽成纯函数便于单测（不依赖任何注入的协作对象）。
     */
    static boolean requiresHarness(List<String> capabilities, Integer compressTriggerMsgs) {
        return capabilities.contains(AgentCapabilities.VIBECODING)
            || capabilities.contains(AgentCapabilities.PLAN)
            || capabilities.contains(AgentCapabilities.SUBAGENT)
            || capabilities.contains(AgentCapabilities.SKILL_LEARNING)
            || capabilities.contains(AgentCapabilities.DYNAMIC_SUBAGENT)
            || capabilities.contains(AgentCapabilities.MEMORY)
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
            .defaultSessionId(WorkspaceRuntimeScope.agent(agentCode))
            .permissionContext(permissionContext)
            .maxIters(agent.getMaxIters() != null ? agent.getMaxIters() : DEFAULT_MAX_ITERS)
            // 中断后无缝续跑：会话被安全中断（见 ChatService#interrupt）后再次调用，先恢复被打断的
            // 挂起工具调用，再处理新消息，与 customer-work-starter 侧的默认行为保持一致。
            .enablePendingToolRecovery(true);
        // 分段耗时采集中间件挂最外层（first = outermost）：onAgent 包裹整次调用统计总耗时，onModelCall/
        // onActing 逐段计时，只读透传不改事件、不打断主链路（异常安全收敛在中间件内）。放在模式闸门之外，
        // 统计口径覆盖完整链路。chat 与 vibecoding 走同一内层 ReActAgent，均被采集。
        builder.middleware(agentCallTimingMiddleware);
        // OTel 追踪紧挨耗时统计（观测类中间件聚在一起，都是只读透传、不改事件）：放在耗时统计之内层，
        // 是因为耗时统计要覆盖"完整链路含 OTel 自身开销"，而 OTel 的 agent span 只需覆盖真正的业务执行；
        // 两者顺序对彼此的数值影响都是微秒级，聚在最外层的意义是——后面所有会短路/改写的中间件
        // （敏感词 BLOCK、模式闸门挂起）发生的事都落在这两个观测中间件的统计与 span 之内，不会被漏采。
        // 未开启 admin.observability.otel.enabled 时该 Bean 不存在，此处跳过。
        if (otelTracingMiddleware != null) {
            builder.middleware(otelTracingMiddleware);
        }
        // 敏感词过滤紧随耗时统计之后、其余中间件之前：入站命中 BLOCK 会直接短路返回安全话术，
        // 越早拦下越好——后面的模式闸门、知识库召回都不必为一条注定要被拦的消息做功。
        // 未开启 admin.content-guard.agent-filter-enabled 时该 Bean 不存在，此处跳过。
        if (sensitiveWordMiddleware != null) {
            builder.middleware(sensitiveWordMiddleware);
        }
        // 执行模式闸门对所有智能体挂载（chat 也生效）：运行时按会话选定的五档模式决策，未指定模式且全局
        // permission-mode=bypass 时纯透传，行为等价于挂载前。顺序关键（first = outermost）：模式闸门在最外层
        // 先看到原始命令挂起/拦改，护栏在内层作最后防线（下方仅 vibecoding 挂载）。
        // 直接注入防护：拦用户输入里的越狱话术，放在靠外层（越早拦住越好，省掉后面整条链路）
        builder.middleware(promptInjectionGuardMiddleware);
        // 出站脱敏：对最终回复里的手机号/身份证/银行卡/邮箱做掩码，与敏感词过滤各管一段
        builder.middleware(maskingMiddleware);
        builder.middleware(executionModeMiddleware);
        // 知识库检索注入：按 agentCode 现建一份（中间件需要知道"本智能体绑了哪些知识库"，构建期绑定
        // 比运行时反推更直接）。挂在这里=注入只影响每次模型调用的输入消息，不进持久化 AgentState，
        // 且 HTTP 检索发生在 reactive 线程（boundedElastic）而非 Tomcat 请求线程——两点都是刻意的，
        // 详见 KnowledgeRetrievalMiddleware 类注释。未绑知识库的智能体在中间件内一次关联表查询即返回。
        builder.middleware(new KnowledgeRetrievalMiddleware(knowledgeRetrievalService, agentCode, knowledgeGapRecorder));
        // 间接注入防护挂在知识库中间件之内层（离模型最近的最后一道隔离）：把工具/MCP 返回结果包进
        // 随机标签隔离块，模型不再把返回体里的文字当指令执行。与上面那条的分工是"谁产出谁负责隔离"——
        // 召回内容由 KnowledgeRetrievalMiddleware 自己包，工具结果由框架产出、只能在这里拦。
        // 两者的系统提示词规则走同一个幂等追加方法，不会写重复。
        builder.middleware(indirectInjectionGuardMiddleware);
        if (capabilities.contains(AgentCapabilities.VIBECODING)) {
            // 只有 vibecoding 能力的 agent 才会跑到文件系统/shell 工具，护栏只对这类 agent 挂载作最后防线——
            // 即便高风险被人工批准/放行，catastrophic 命令仍会被护栏改写，护栏不被绕过（需求 §4.4.2.5「两者叠加」）。
            builder.middleware(sandboxGuardMiddleware);
        }
        if (capabilities.contains(AgentCapabilities.TASKLIST)) {
            // 任务列表：内层 ReActAgent 自带能力，不依赖 Harness
            builder.enableTaskList(true);
        }
        if (capabilities.contains(AgentCapabilities.SKILL_LEARNING)) {
            // 互动学习新技能（内层半边）：MetaTool 允许 Agent 自主编排技能；Harness 半边见 buildHarnessAgent
            builder.enableMetaTool(true);
        }
        applyToolExecutionConfig(builder, agent);

        Set<String> skillToolNames = new HashSet<>();
        SkillBox skillBox = buildSkillBox(agent, toolkit, skillToolNames);
        if (skillBox != null) {
            builder.skillBox(skillBox);
        }
        toolSourceCache.put(WorkspaceRuntimeScope.agent(agentCode),
            new ToolSourceInfo(skillToolNames, mcpToolNames));
        // 登记本智能体的 MCP/Skill 工具名，供采集中间件把 onActing 分段归为 MCP/SKILL（未登记者默认 TOOL）
        agentCallToolKindRegistry.registerMcpTools(mcpToolNames);
        agentCallToolKindRegistry.registerSkillTools(skillToolNames);
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
        boolean vibecoding = capabilities.contains(AgentCapabilities.VIBECODING);

        // Docker 模式下 HarnessAgent 内部的 SessionSandboxStateStore 会给沙箱状态槽位拼出带 "/"
        // 的 sessionId（IsolationScope 四种取值全部如此，框架侧硬编码），而 MysqlAgentStateStore
        // 拒绝接受含路径分隔符的 sessionId，两者组合必然抛异常——用 SandboxSafeAgentStateStore
        // 包一层转义规避，local 模式与未挂 filesystem 沙箱的升级路径不受影响（见该类 Javadoc）。
        AgentStateStore harnessStateStore = vibecoding && sandboxProperties.isDockerMode()
            ? new SandboxSafeAgentStateStore(stateStore) : stateStore;
        Path workspace = resolveWorkspace(agentCode);
        HarnessAgent.Builder harnessBuilder = HarnessAgent.Builder.fromAgent(inner)
            .stateStore(harnessStateStore)
            .defaultSessionId(WorkspaceRuntimeScope.agent(agentCode))
            .permissionContext(permissionContext)
            // 框架 #1644 缓解：HarnessAgent 未显式设置 generateOptions 时 streamEvents() 会 NPE，
            // 这里保证非空即可，实际推理参数仍由内层 ReActAgent 的模型配置决定
            .generateOptions(GenerateOptions.builder().build())
            // 后台委派任务改落 MySQL：框架默认的 WorkspaceTaskRepository 把状态写在工作区文件里，
            // 只够父智能体自己回头查，管理台没法跨会话列出/取消，工作区被清理时记录还会一起没。
            .taskRepository(taskRepository)
            .workspace(workspace);

        if (vibecoding) {
            if (sandboxProperties.isDockerMode()) {
                harnessBuilder.filesystem(buildDockerFilesystemSpec(sandboxProperties, agentCode));
            } else {
                harnessBuilder.filesystem(buildLocalFilesystemSpec());
            }
        }
        if (capabilities.contains(AgentCapabilities.PLAN)) {
            // 计划模式：只读规划期禁 shell，计划文件持久化到 workspace/plans
            harnessBuilder.enablePlanMode()
                .allowShellInPlanMode(false)
                .planFileDirectory(workspace.resolve(PLAN_DIR_NAME).toString());
        }
        if (capabilities.contains(AgentCapabilities.SKILL_LEARNING)) {
            // 互动学习新技能（Harness 半边）：技能管理工具 + SkillCurator 自动沉淀成功模式
            harnessBuilder.enableSkillManageTool(true)
                .enableSkillCurator(SkillCuratorConfig.defaults());
        }
        if (capabilities.contains(AgentCapabilities.MEMORY)) {
            // 跨会话长期记忆（分层记忆）：对话事实自动 flush 到 workspace/MEMORY.md 并定期 consolidation，
            // 按 agentCode 隔离（一个智能体一份记忆，全部会话共享）。workspace 文件只是框架的工作副本，
            // 权威存储在 AgentMemoryStore（默认落库）：构建时水合到 workspace，对话轮次结束后由
            // ChatService 回写（见 AgentMemorySyncService）
            memorySyncService.hydrate(agentCode, workspace);
            harnessBuilder.memory(MemoryConfig.builder().model(model).build());
            log.info("[workspace] layered memory enabled: agentCode={}", agentCode);
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
        if (capabilities.contains(AgentCapabilities.SUBAGENT)) {
            registerSubagents(harnessBuilder, agent, visited);
        }
        if (capabilities.contains(AgentCapabilities.DYNAMIC_SUBAGENT)) {
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
            if (sub == null || sub.getStatus() == null || sub.getStatus() != StatusFlags.ENABLED) {
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
     *
     * <p><b>产物同步（P1-3，bind mount 挂 agent 工作区根）</b>：把宿主机 agent 工作区根
     * {@code {agentCode}/} 整目录挂进容器工作区根 {@code /workspace}（经 {@code docker run -v}），
     * 容器内 workspace-relative 的一切写入（{@code sessions/{sessionId}/} 会话产物、跨会话
     * {@code MEMORY.md}、Plan Mode 的 {@code plans/}）都实时落宿主机同一目录——与 local 模式的
     * 目录语义完全一致，宿主机侧文件树 / file_change / Git 助手 / 回滚 / test_report 无需改造可用。
     * 挂根而非只挂 {@code sessions/} 的原因：框架 MEMORY.md/plans 写入走 Filesystem 抽象（docker 模式
     * = 容器内 workspace 根），只挂子目录会让这些跨会话文件留在容器里随容器丢失；且注入 {@code --user}
     * 后容器内 {@code /workspace} 根（dockerd 以 root 建的挂载点）非 root 不可写，挂根让 {@code /workspace}
     * 属主即宿主机用户，两个问题一并消除。会话间越权面与 local 模式等同（LocalFilesystemSpec 的
     * workspace 本就是 agent 根），HTTP 侧 sessionId 穿越防御（{@link #resolveSessionWorkspace}）不受影响。</p>
     *
     * <p>同时关闭框架默认的 workspace projection（启动时把宿主机工作区 tar 快照式拷进容器的机制）——
     * bind mount 已实时双向可见，projection 与挂载目录重叠且冗余。{@code HOME=/workspace} 供
     * {@code --user} 下无 passwd 条目的进程使用（mvn 等需要可写 HOME，落在挂载区内且不进 sessions/
     * 产物树）。静态包私有便于 {@code DockerSandboxIntegrationTest} 直接消费生产装配（不必 mock 全套依赖）。</p>
     */
    static SandboxFilesystemSpec buildDockerFilesystemSpec(AdminSandboxProperties sandboxProperties, String agentCode) {
        AdminSandboxProperties.Docker docker = sandboxProperties.getDocker();
        Path hostWorkspaceRoot = prepareHostWorkspaceRoot(agentCode);
        List<String> runArgs = new ArrayList<>();
        // P1-3 核心：宿主机 agent 工作区根 ↔ 容器 /workspace 双向实时可见
        runArgs.add("-v");
        runArgs.add(hostWorkspaceRoot + ":" + CONTAINER_WORKSPACE_ROOT + ":rw");
        if (HOST_UID_GID != null) {
            // 容器进程 uid/gid 对齐宿主机 JVM，bind mount 写出的产物属主即宿主机用户（Linux 属主问题的根治）
            runArgs.add("--user");
            runArgs.add(HOST_UID_GID);
        } else {
            log.info("[workspace] host uid/gid unresolved, docker sandbox runs as image default user, agentCode={}", agentCode);
        }
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setEnvironment(Map.of("HOME", CONTAINER_WORKSPACE_ROOT));
        return new DockerFilesystemSpec()
            .image(docker.getImage())
            .memorySizeBytes(docker.getMemoryMb() * BYTES_PER_MB)
            .cpuCount(docker.getCpuCount())
            .network(docker.getNetwork())
            .additionalRunArgs(runArgs)
            .workspaceSpec(workspaceSpec)
            .workspaceProjectionEnabled(false)
            .isolationScope(IsolationScope.AGENT);
    }

    /**
     * bind mount 前置：预建宿主机 agent 工作区根及 {@code sessions/} 子目录并 fast fail——若交给
     * {@code docker -v} 自动补建，缺失目录会以 root 属主创建，后续宿主机侧 git/回滚必然撞权限，
     * 这正是本方法要规避的场景，建不出来就不该继续挂载（沙箱装配随之失败，错误信息直达调用方）。
     * 返回绝对 normalize 路径，与 {@link #resolveSessionWorkspace} 的解析同源（同一 {@code WORKSPACE_ROOT}
     * 相对 JVM 工作目录），保证容器内写入与宿主机读取指向同一目录。
     */
    private static Path prepareHostWorkspaceRoot(String agentCode) {
        Path hostWorkspaceRoot = Path.of(WORKSPACE_ROOT, WorkspaceRuntimeScope.agent(agentCode))
            .toAbsolutePath().normalize();
        try {
            Files.createDirectories(hostWorkspaceRoot.resolve(SESSIONS_DIR_NAME));
        } catch (Exception e) {
            log.error("[workspace] create host workspace dir for bind mount failed, code={}, agentCode={}",
                "SANDBOX_BIND_MOUNT_INIT_ERROR", agentCode, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "docker 沙箱工作区目录创建失败: " + hostWorkspaceRoot);
        }
        return hostWorkspaceRoot;
    }

    /**
     * 探测宿主机 JVM 进程的 {@code uid:gid}（POSIX {@code id -u}/{@code id -g}），供
     * {@code docker run --user} 使用；任何异常（命令缺失/超时/非 POSIX 平台）返回 null 表示不注入。
     * 类加载时执行一次，进程生命周期内 uid 不会变。
     */
    private static String resolveHostUidGid() {
        try {
            String uid = execIdCommand("-u");
            String gid = execIdCommand("-g");
            if (StringUtils.hasText(uid) && StringUtils.hasText(gid)) {
                return uid + ":" + gid;
            }
        } catch (Exception e) {
            log.error("[workspace] resolve host uid/gid failed, code={}", "SANDBOX_UID_RESOLVE_FAIL", e);
        }
        return null;
    }

    /** 执行 {@code id <flag>} 并返回 trim 后的输出；非零退出码/超时返回 null。 */
    private static String execIdCommand(String flag) throws Exception {
        Process process = new ProcessBuilder("id", flag).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!process.waitFor(ID_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        return process.exitValue() == 0 ? output : null;
    }

    /**
     * VibeCoding 沙箱工作区路径（智能体根目录）：{@code {临时根}/admin-workspace/{agentCode}}。
     * 仅供快照根路径使用，Agent 运行时请使用 {@link #resolveSessionWorkspace(String, String)} 按会话隔离。
     */
    public Path resolveWorkspace(String agentCode) {
        Path workspace = Path.of(WORKSPACE_ROOT, WorkspaceRuntimeScope.agent(agentCode));
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[workspace] create workspace dir failed, code={}, agentCode={}", "WORKSPACE_INIT_ERROR", agentCode, e);
        }
        return workspace;
    }

    /**
     * VibeCoding 沙箱工作区路径（会话级隔离）：{@code {临时根}/admin-workspace/{agentCode}/sessions/{sessionId}}。
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
        Path sessionsRoot = Path.of(WORKSPACE_ROOT, WorkspaceRuntimeScope.agent(agentCode), "sessions").normalize();
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
        // 恢复权威副本：工作区落在系统临时目录（会被 OS 清理）、容器化部署更是一销毁就没了，
        // 而会话产出物是用户生成的代码、不是可重建的派生物。本方法是所有会话级功能解析路径的公共入口，
        // 挂在这里能覆盖 stream / files / file-content / rollback 全部链路。
        // 仅在本地目录为空时真正拉取（见 SessionWorkspaceStorage#hydrate），故重复调用无额外开销。
        if (sessionWorkspaceStorage != null) {
            sessionWorkspaceStorage.hydrate(agentCode, safeSession, workspace);
        }
        return workspace;
    }

    /**
     * 把会话工作区保存回权威存储（对象存储）。产出物写入之后必须调用一次，否则本地临时目录一被清理就丢。
     *
     * <p>调用点覆盖三条会造成写入的链路：对话轮次结束、手工保存文件、一键回滚。
     * 未启用持久化时静默跳过；失败只记 error，不打断主链路（见 {@link SessionWorkspaceStorage#persist}）。</p>
     */
    public void persistSessionWorkspace(String agentCode, String sessionId) {
        if (sessionWorkspaceStorage == null) {
            return;
        }
        String safeSession = requireSafeSessionId(sessionId);
        sessionWorkspaceStorage.persist(agentCode, safeSession,
            Path.of(WORKSPACE_ROOT, WorkspaceRuntimeScope.agent(agentCode), "sessions", safeSession).normalize());
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
        if (agent.getStatus() == null || agent.getStatus() != StatusFlags.ENABLED) {
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
            if (tool.getEnabled() == null || tool.getEnabled() != StatusFlags.ENABLED) {
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
     * 读 {@code ai_agent_skill} 关联行，把完整技能包（SKILL.md 正文 + ai_skill_file 附属文件）落盘后
     * 复用 FileSystemSkillRepository 加载——附属文件不落盘的话，SKILL.md 里引用的 references/scripts
     * 在运行时不存在，技能功能不完整。落盘前先清空该 skill 目录，避免上一版残留文件混入。
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
                deleteRecursively(skillSubDir);
                Files.createDirectories(skillSubDir);
                Files.writeString(skillSubDir.resolve(AgentFileNames.SKILL_MD), skill.getContent());
                // 附属文件路径合法性在 SkillService 保存时已统一校验，此处直接落盘
                for (AiSkillFile skillFile : skillFileMapper.selectList(
                        new LambdaQueryWrapper<AiSkillFile>().eq(AiSkillFile::getSkillId, skill.getId()))) {
                    Path target = skillSubDir.resolve(skillFile.getFilePath());
                    Files.createDirectories(target.getParent());
                    Files.write(target, skillFile.getContent());
                }
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

    /** 递归删除目录（不存在则跳过），用于技能落盘前清理旧产物。 */
    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : ordered) {
                Files.delete(p);
            }
        }
    }

    private List<String> parseCapabilities(String capabilities) {
        return StringUtils.hasText(capabilities)
            ? Arrays.asList(capabilities.split(AgentCapabilities.DELIMITER)) : List.of();
    }
}
