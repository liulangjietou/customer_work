package com.richard.fyoung.customeradmin.workspace.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.runtime.AdminMcpFactory;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@link FileSystemSkillRepository} 加载（不自造 Skill 解析逻辑）→ ⑤ {@link ReActAgent.Builder} 组装；
 * 若 {@code capabilities} 含 {@code vibecoding}，用 {@link HarnessAgent.Builder#fromAgent} 在内层
 * ReActAgent 上叠加本地沙箱（workspace 限定到 {@code ./data/admin-workspace/{agentCode}}）。</p>
 *
 * <p>本类只负责"从零构建一次"，不做缓存——缓存由 {@link AgentInstanceCache} 负责。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminAgentInstanceFactory {

    private static final Logger log = LoggerFactory.getLogger(AdminAgentInstanceFactory.class);

    private static final String CAPABILITY_VIBECODING = "vibecoding";
    private static final String CAPABILITY_DELIMITER = ",";
    private static final int STATUS_ENABLED = 1;
    private static final int DEFAULT_MAX_ITERS = 10;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final String WORKSPACE_ROOT = "./data/admin-workspace";
    private static final String SKILL_ROOT = "./data/admin-skills";
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个乐于助人的智能助手。不清楚的问题说不知道，需要人工确认，不要瞎编或者胡说八道。";

    private final AiAgentMapper agentMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final AiMcpMapper mcpMapper;
    private final AiSkillMapper skillMapper;
    private final AdminModelFactory modelFactory;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AgentStateStore stateStore;
    private final PermissionContextState permissionContext;
    private final AdminMcpFactory mcpFactory;
    private final AdminSandboxProperties sandboxProperties;
    private final SandboxGuardMiddleware sandboxGuardMiddleware;

    /**
     * {@code agentCode -> ToolSourceInfo}：{@link #build} 每次重建都会覆盖写入，天然跟着
     * {@link AgentInstanceCache} 的重建节奏保持新鲜，不需要额外的失效联动。
     */
    private final ConcurrentHashMap<String, ToolSourceInfo> toolSourceCache = new ConcurrentHashMap<>();

    public AdminAgentInstanceFactory(AiAgentMapper agentMapper, AiAgentMcpMapper agentMcpMapper,
                                      AiAgentSkillMapper agentSkillMapper, AiModelConfigMapper modelConfigMapper,
                                      AiMcpMapper mcpMapper, AiSkillMapper skillMapper,
                                      AdminModelFactory modelFactory, AesGcmCryptoUtil cryptoUtil,
                                      AgentStateStore stateStore, PermissionContextState permissionContext,
                                      AdminMcpFactory mcpFactory, AdminSandboxProperties sandboxProperties,
                                      SandboxGuardMiddleware sandboxGuardMiddleware) {
        this.agentMapper = agentMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.mcpMapper = mcpMapper;
        this.skillMapper = skillMapper;
        this.modelFactory = modelFactory;
        this.cryptoUtil = cryptoUtil;
        this.stateStore = stateStore;
        this.permissionContext = permissionContext;
        this.mcpFactory = mcpFactory;
        this.sandboxProperties = sandboxProperties;
        this.sandboxGuardMiddleware = sandboxGuardMiddleware;
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
        return buildModel(agent.getModelId());
    }

    /** 从零构建一次智能体实例（供 {@link AgentInstanceCache} 惰性重建时调用）。 */
    public Agent build(String agentCode) {
        AiAgent agent = requireEnabledAgent(agentCode);
        List<String> capabilities = parseCapabilities(agent.getCapabilities());

        Model model = buildModel(agent.getModelId());
        Set<String> mcpToolNames = new HashSet<>();
        Toolkit toolkit = buildToolkit(agent.getId(), mcpToolNames);

        ReActAgent.Builder builder = ReActAgent.builder()
            .name("AdminAgent-" + agentCode)
            .sysPrompt(StringUtils.hasText(agent.getSystemPrompt()) ? agent.getSystemPrompt() : DEFAULT_SYSTEM_PROMPT)
            .model(model)
            .toolkit(toolkit)
            .stateStore(stateStore)
            .defaultSessionId(agentCode)
            .permissionContext(permissionContext)
            .maxIters(DEFAULT_MAX_ITERS);
        if (capabilities.contains(CAPABILITY_VIBECODING)) {
            // 只有 vibecoding 能力的 agent 才会跑到文件系统/shell 工具，护栏只对这类 agent 挂载。
            builder.middleware(sandboxGuardMiddleware);
        }

        Set<String> skillToolNames = new HashSet<>();
        SkillBox skillBox = buildSkillBox(agent, toolkit, skillToolNames);
        if (skillBox != null) {
            builder.skillBox(skillBox);
        }
        toolSourceCache.put(agentCode, new ToolSourceInfo(skillToolNames, mcpToolNames));

        ReActAgent inner = builder.build();
        if (!capabilities.contains(CAPABILITY_VIBECODING)) {
            log.info("[workspace] agent built: agentCode={} capabilities={}", agentCode, capabilities);
            return inner;
        }

        // Docker 模式下 HarnessAgent 内部的 SessionSandboxStateStore 会给沙箱状态槽位拼出带 "/"
        // 的 sessionId（IsolationScope 四种取值全部如此，框架侧硬编码），而 MysqlAgentStateStore
        // 拒绝接受含路径分隔符的 sessionId，两者组合必然抛异常——用 SandboxSafeAgentStateStore
        // 包一层转义规避，local 模式不受影响（见该类 Javadoc）。
        AgentStateStore harnessStateStore = sandboxProperties.isDockerMode()
            ? new SandboxSafeAgentStateStore(stateStore) : stateStore;
        HarnessAgent.Builder harnessBuilder = HarnessAgent.Builder.fromAgent(inner)
            .stateStore(harnessStateStore)
            .defaultSessionId(agentCode)
            .permissionContext(permissionContext)
            .generateOptions(GenerateOptions.builder().build())
            .workspace(resolveWorkspace(agentCode));
        if (sandboxProperties.isDockerMode()) {
            harnessBuilder.filesystem(buildDockerFilesystemSpec());
        } else {
            harnessBuilder.filesystem(buildLocalFilesystemSpec());
        }
        HarnessAgent harnessAgent = harnessBuilder.build();
        log.info("[workspace] harness agent built (vibecoding): agentCode={} sandboxMode={}",
            agentCode, sandboxProperties.getMode());
        return harnessAgent;
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
     */
    public Path resolveSessionWorkspace(String agentCode, String sessionId) {
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        Path workspace = Path.of(WORKSPACE_ROOT, agentCode, "sessions", safeSession);
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.error("[workspace] create session workspace dir failed, code={}, agentCode={}, sessionId={}",
                "SESSION_WORKSPACE_INIT_ERROR", agentCode, safeSession, e);
        }
        return workspace;
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
