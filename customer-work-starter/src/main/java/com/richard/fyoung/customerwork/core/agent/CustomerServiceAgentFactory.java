package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.NacosPromptService;
import com.richard.fyoung.customerwork.core.memory.LongTermMemoryProvider;
import com.richard.fyoung.customerwork.data.rag.KnowledgeProvider;
import com.richard.fyoung.customerwork.data.skill.MysqlSkillMaterializer;
import com.richard.fyoung.customerwork.tool.HigressToolkitConfigurer;
import com.richard.fyoung.customerwork.tool.McpToolkitConfigurer;
import com.richard.fyoung.customerwork.tool.DefaultActiveGroupsToolkit;
import com.richard.fyoung.customerwork.tool.ToolRegistrar;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.recorder.JsonlTraceExporter;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.richard.fyoung.customerwork.infra.config.properties.SkillProperties;

/**
 * 客服 Agent 工厂（对应 ③主 Agent 与 ④子 Agent 执行层）。
 *
 * <p>按会话装配一个 {@link ReActAgent}，覆盖截图所列的全部可落地特性：</p>
 * <ul>
 *   <li>ReActAgent 推理 + maxIters 上限；</li>
 *   <li>Toolkit + Tool Group + 可选 Meta-Tool（工具集成）；</li>
 *   <li>PlanNotebook 任务规划；</li>
 *   <li>短期记忆 / 可选智能上下文压缩（AutoContext）；</li>
 *   <li>多租户长期记忆；</li>
 *   <li>RAG 知识检索；</li>
 *   <li>Skill 技能库；</li>
 *   <li>MCP 接入；</li>
 *   <li>可观测 Hook + 可选 JSONL trace 导出；</li>
 *   <li>Human-in-the-Loop 工具级人工确认 Hook。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Component
public class CustomerServiceAgentFactory implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceAgentFactory.class);

    private static final String SYSTEM_PROMPT = """
        你是一名专业、耐心的电商智能客服助手。请严格遵循以下规则：
        1. 先理解用户意图（咨询 / 订单查询 / 售后退款 / 投诉）。
        2. 咨询类问题优先依据知识库 / RAG 检索结果回答，并保留来源标注。
        3. 订单 / 物流类问题调用订单工具组查询后再回答，可一次并行查询多项。
        4. 退款类问题必须先调用退款资格校验工具，通过后才生成退款工单；
           涉及资金的退款只生成"待人工确认工单"，绝不承诺已直接打款。
        5. 当用户情绪强烈、明确要求人工、投诉升级或涉及大额 / 高风险时，
           立即调用人工转接工具升级到人工坐席。
        6. 回答简洁、准确、有礼貌；信息不足时主动向用户追问订单号等关键信息。
        7. 不得编造订单、物流、政策等事实；工具 / 知识库查不到就如实说明并引导用户。
        8. 涉及多步骤的复杂任务，可借助计划工具拆解为子任务并按序推进。
        """;

    private final Model model;
    private final CustomerWorkProperties properties;
    private final LongTermMemoryProvider longTermMemoryProvider;
    private final KnowledgeProvider knowledgeProvider;
    private final McpToolkitConfigurer mcpToolkitConfigurer;
    private final HigressToolkitConfigurer higressToolkitConfigurer;
    private final ToolRegistrar toolRegistrar;
    /** 状态外置存储：2.0 中 Agent 无状态，会话状态按 (userId, sessionId) 自动持久化到此。 */
    private final AgentStateStore stateStore;
    /** 权限上下文（2.0 Permission System）：声明式控制工具授权，对主 Agent 原生生效。 */
    private final PermissionContextState permissionContext;
    private final NacosPromptService nacosPromptService;
    /** 租户解析（单一职责，与质检/诊断链路共用同一实现）。 */
    private final TenantResolver tenantResolver;
    /** 工具归类登记表：skill 工具在此登记名称，供分段耗时统计按类归段。 */
    private final ToolKindRegistry toolKindRegistry;
    /**
     * 治理中间件装配器：全链路<b>唯一</b>装配入口，本工厂与 {@code MultiAgentOrchestrator} 共用同一份。
     * 新增治理能力只改 {@link AgentGovernanceAssembler} 一处，所有对话路径自动获得。
     */
    private final AgentGovernanceAssembler governanceAssembler;
    /** MySQL 技能物化器：{@code skill.repository=mysql} 时才取用，持久化环境未激活时取不到。 */
    private final ObjectProvider<MysqlSkillMaterializer> skillMaterializerProvider;

    /** 共享的 trace 导出器（AutoCloseable，进程级单例）。 */
    private volatile JsonlTraceExporter traceExporter;

    public CustomerServiceAgentFactory(Model model,
                                       CustomerWorkProperties properties,
                                       LongTermMemoryProvider longTermMemoryProvider,
                                       KnowledgeProvider knowledgeProvider,
                                       McpToolkitConfigurer mcpToolkitConfigurer,
                                       HigressToolkitConfigurer higressToolkitConfigurer,
                                       ToolRegistrar toolRegistrar,
                                       AgentStateStore stateStore,
                                       PermissionContextState permissionContext,
                                       NacosPromptService nacosPromptService,
                                       TenantResolver tenantResolver,
                                       ToolKindRegistry toolKindRegistry,
                                       AgentGovernanceAssembler governanceAssembler,
                                       ObjectProvider<MysqlSkillMaterializer> skillMaterializerProvider) {
        this.model = model;
        this.properties = properties;
        this.longTermMemoryProvider = longTermMemoryProvider;
        this.knowledgeProvider = knowledgeProvider;
        this.mcpToolkitConfigurer = mcpToolkitConfigurer;
        this.higressToolkitConfigurer = higressToolkitConfigurer;
        this.toolRegistrar = toolRegistrar;
        this.stateStore = stateStore;
        this.permissionContext = permissionContext;
        this.nacosPromptService = nacosPromptService;
        this.tenantResolver = tenantResolver;
        this.toolKindRegistry = toolKindRegistry;
        this.governanceAssembler = governanceAssembler;
        this.skillMaterializerProvider = skillMaterializerProvider;
    }

    /**
     * 构造一次 Agent 调用的运行时上下文：把"会话 ID"映射为 2.0 的 {@code (userId, sessionId)}。
     *
     * <p>实现收敛在 {@link AgentGovernanceAssembler#contextFor(String)}，本方法只做转发——
     * 多 Agent 编排器走的是同一份实现，两条路径的 {@code (userId, sessionId)} 口径必须一致，
     * 否则同一个会话在不同路径上会落到框架的不同状态槽位。</p>
     */
    public RuntimeContext contextFor(String sessionId) {
        return governanceAssembler.contextFor(sessionId);
    }

    /**
     * 生效的系统提示词：优先 Nacos 配置中心下发，缺省回退内置提示词；统一追加运行期事实注入。
     *
     * <p>public 而非包级可见：{@code PromptVersionTracker} 要拿它算内容指纹做效果归因。
     * "运行时实际生效的提示词"本就该对外可见——配置中心里那份记的是"发布了什么"，
     * 灰度未覆盖或推送未到达时两者会不一致，而能对上评测指标的只有前者。</p>
     */
    public String systemPrompt() {
        return nacosPromptService.currentPrompt().orElse(SYSTEM_PROMPT) + runtimeFacts();
    }

    /**
     * 运行期事实注入（Nacos 覆盖与内置提示词都追加）：
     * 模型训练知识存在时间滞后，不注入当前日期会把合法单号推理成"未来日期不合逻辑"而拒查。
     */
    private String runtimeFacts() {
        return "\n补充事实与约束：\n"
            + "- 当前日期：" + java.time.LocalDate.now() + "（以此为准判断时间，不要依赖你训练记忆里的年份）。\n"
            + "- 订单号 / 单据编号不要凭格式或日期做有效性猜测，一律直接调用对应查询工具核实。\n";
    }

    /** 无会话上下文的工具体系（保留重载委托 null，保证既有测试无需改动即绿）。 */
    Toolkit buildToolkit() {
        return buildToolkit(null);
    }

    /**
     * 构建工具体系：按业务域分组注册工具，可选注册元工具与 MCP 工具。
     *
     * @param sessionId 会话标识；非空时转人工工具以真实会话驱动工单域
     */
    Toolkit buildToolkit(String sessionId) {
        Toolkit toolkit = new DefaultActiveGroupsToolkit();

        // 业务工具按域分组注册（壳 + 可替换后端），透传真实会话以驱动工单域
        toolRegistrar.registerBusinessTools(toolkit, sessionId);

        if (properties.getAgent().isMetaToolEnabled()) {
            toolkit.registerMetaTool();
            log.info("已启用 Meta-Tool（元工具）");
        }

        // MCP：把存量 HTTP 系统接成 Agent 工具（默认关闭）
        mcpToolkitConfigurer.configure(toolkit);
        // Higress AI 网关：按需工具发现 / 流量治理（默认关闭）
        higressToolkitConfigurer.configure(toolkit);

        return toolkit;
    }

    /**
     * 为指定会话创建一个客服 Agent。
     *
     * @param sessionId 会话标识（可含租户前缀如 tenantA:conv-1）
     */
    public ReActAgent createAgent(String sessionId) {
        log.info("创建客服 Agent，会话: {}", sessionId);

        Toolkit toolkit = buildToolkit(sessionId);

        ReActAgent.Builder builder = ReActAgent.builder()
            .name("CustomerServiceAgent-" + sessionId)
            .sysPrompt(systemPrompt())
            .model(model)
            .toolkit(toolkit)
            // 2.0：状态外置到 StateStore，按 (userId, sessionId) 自动加载/持久化短期会话状态
            .stateStore(stateStore)
            .defaultSessionId(sessionId)
            // 2.0 权限系统：声明式工具授权（与 HumanApprovalMiddleware 形成双层闸门）
            .permissionContext(permissionContext)
            .maxIters(properties.getAgent().getMaxIters())
            // 中断后无缝恢复：保留并恢复被打断的待执行工具调用
            .enablePendingToolRecovery(properties.getInterrupt().isPendingToolRecoveryEnabled());

        // 治理中间件（可观测 / 人工确认 / 脱敏 / 审计 / 护栏 / 租户 / 分段耗时与 token 计量）：
        // 统一走装配器，与多 Agent 编排器共用同一份装配，杜绝"能力只接在一条路径上"
        governanceAssembler.applyTo(builder);

        // 可观测：JSONL trace 导出（框架 Hook，数据飞轮采集）
        if (properties.getObservability().isTraceEnabled()) {
            builder.hook(traceExporter());
        }

        // 多租户长期记忆（memory / 百炼，由 Provider 选择）
        if (properties.getMemory().isLongTermEnabled()) {
            String tenantId = resolveTenant(sessionId);
            builder.longTermMemory(longTermMemoryProvider.create(tenantId))
                .longTermMemoryMode(LongTermMemoryMode.BOTH);
        }

        // RAG 知识检索（memory / 百炼企业知识库，由 Provider 选择）
        if (properties.getRag().isEnabled()) {
            builder.knowledge(knowledgeProvider.get()).ragMode(RAGMode.AGENTIC);
        }

        // Skill 技能库
        if (properties.getSkill().isEnabled()) {
            SkillBox skillBox = buildSkillBox(toolkit);
            if (skillBox != null) {
                builder.skillBox(skillBox);
                // 代码执行技能：2.0 由 Builder 内置开关承接（替代 1.x 的 skillBox.codeExecution() 流式构建）
                SkillProperties skillCfg = properties.getSkill();
                if (skillCfg.isCodeExecutionEnabled()) {
                    builder.skillCodeExecutionEnabled(true)
                        .skillWorkDir(Path.of(skillCfg.getCodeExecutionWorkDir()));
                    log.info("[Skill] code execution enabled, workDir={}", skillCfg.getCodeExecutionWorkDir());
                }
            }
        }

        return builder.build();
    }

    /**
     * 从 MySQL 加载技能：先把 {@code cw_skill} / {@code cw_skill_file} 物化到 {@code skill.directory}，
     * 再交框架的 {@link FileSystemSkillRepository} 读——框架只认文件系统，落盘是框架约束而非存储选型，
     * 那个目录因此是每次启动重建的缓存，不是权威来源。
     *
     * <p>物化器取不到（持久化环境未激活）或物化失败时，<b>降级</b>读该目录里已有的内容：上一次物化的产物
     * 还在的话技能仍可用，好过整个技能能力消失。物化器缺席属于配置错配，记 error 便于排查。</p>
     */
    private List<AgentSkill> loadSkillsFromMysql(SkillProperties cfg) throws java.io.IOException {
        java.nio.file.Path dir = Path.of(cfg.getDirectory());
        MysqlSkillMaterializer materializer =
            skillMaterializerProvider == null ? null : skillMaterializerProvider.getIfAvailable();
        if (materializer == null) {
            log.error("[Skill] mysql 仓库但物化器不可用（持久化环境未激活），改读磁盘存量, code={}, dir={}",
                "SKILL-MATERIALIZER-MISSING", dir.toAbsolutePath());
        } else {
            try {
                materializer.materializeTo(dir);
            } catch (Exception e) {
                log.error("[Skill] 技能物化失败，改读磁盘存量, code={}, dir={}",
                    "SKILL-MATERIALIZE-FAIL", dir.toAbsolutePath(), e);
            }
        }
        java.nio.file.Files.createDirectories(dir);
        // 物化目录是 MySQL 的投影，写回它没有意义（下次启动即被覆盖），故一律只读挂载
        List<AgentSkill> skills = new FileSystemSkillRepository(dir, false).getAllSkills();
        log.info("[Skill] mysql 仓库（物化目录 {}，只读），技能数={}", dir.toAbsolutePath(), skills.size());
        return skills;
    }

    /** 加载技能并注册进 SkillBox（支持 mysql 权威 / classpath 只读 / filesystem 可写自进化）。 */
    private SkillBox buildSkillBox(Toolkit toolkit) {
        SkillProperties cfg = properties.getSkill();
        try {
            List<AgentSkill> skills;
            String repository = cfg.getRepository();
            if ("mysql".equalsIgnoreCase(repository)) {
                skills = loadSkillsFromMysql(cfg);
            } else if ("filesystem".equalsIgnoreCase(repository)) {
                java.nio.file.Path dir = Path.of(cfg.getDirectory());
                java.nio.file.Files.createDirectories(dir);
                skills = new FileSystemSkillRepository(dir, cfg.isWritable()).getAllSkills();
                log.info("[Skill] filesystem 仓库({}, writable={})", dir.toAbsolutePath(), cfg.isWritable());
            } else {
                skills = new ClasspathSkillRepository(cfg.getLocation()).getAllSkills();
            }
            // 快照注册前工具名，注册 skill 后取增量即为 skill 贡献的工具，登记为 SKILL 类别
            // （用 toolkit 实际工具名做键，与 onActing 的 ToolUseBlock.getName() 一致）
            java.util.Set<String> beforeSkill = new java.util.HashSet<>(toolkit.getToolNames());
            SkillBox skillBox = new SkillBox(toolkit);
            for (AgentSkill skill : skills) {
                skillBox.registerSkill(skill);
            }
            java.util.Set<String> skillTools = new java.util.HashSet<>(toolkit.getToolNames());
            skillTools.removeAll(beforeSkill);
            // 兜底：skill 可能以懒激活形式尚未落 toolkit，同时登记 skillId / skillName，覆盖 onActing 可能出现的两种名
            skillTools.addAll(skillBox.getAllSkillIds());
            for (AgentSkill skill : skills) {
                if (skill.getName() != null) {
                    skillTools.add(skill.getName());
                }
            }
            toolKindRegistry.registerSkillTools(skillTools);
            // 运行时加载技能工具：允许 Agent 按需自行加载技能（技能自进化）
            if (cfg.isRuntimeLoadToolEnabled()) {
                skillBox.registerSkillLoadTool();
                log.info("[Skill] runtime skill-load tool registered");
            }
            // 代码执行 workDir：2.0 工作目录在 SkillBox 上设置，开关在 Builder 上（见 createAgent）
            if (cfg.isCodeExecutionEnabled()) {
                skillBox.setWorkDir(Path.of(cfg.getCodeExecutionWorkDir()));
            }
            log.info("[Skill] loaded {} skills: {}", skillBox.getAllSkillIds().size(),
                skillBox.getAllSkillIds());
            return skillBox;
        } catch (Exception e) {
            log.error("[Skill] skill loading failed (skip skill wiring), code={}", "SKILL_LOAD_ERROR", e);
            return null;
        }
    }

    private JsonlTraceExporter traceExporter() {
        if (traceExporter == null) {
            synchronized (this) {
                if (traceExporter == null) {
                    traceExporter = JsonlTraceExporter
                        .builder(Path.of(properties.getObservability().getTraceFile()))
                        .append(true)
                        .flushEveryLine(true)
                        .build();
                    log.info("[OTEL] JSONL trace 导出已启用: {}",
                        properties.getObservability().getTraceFile());
                }
            }
        }
        return traceExporter;
    }

    /**
     * 从 sessionId 解析租户 ID：sessionId 形如 {@code tenantA:conv-1} 时取分隔符前部分，
     * 使同租户不同会话共享长期记忆；无分隔符则整个 sessionId 作为租户。
     */
    String resolveTenant(String sessionId) {
        return tenantResolver.resolveDataScope(sessionId);
    }

    /** 容器关闭时优雅释放 trace 导出器（AutoCloseable）。 */
    @Override
    public void destroy() {
        if (traceExporter != null) {
            try {
                traceExporter.close();
                log.info("[OTEL] trace 导出器已关闭");
            } catch (Exception e) {
                log.error("[OTEL] trace exporter close failed, code={}, path={}",
                    "OTEL_TRACER_CLOSE_FAIL", properties.getObservability().getTraceFile(), e);
            }
        }
    }
}
