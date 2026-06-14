package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.config.NacosPromptService;
import com.richard.fyoung.customerwork.memory.ContextMemoryFactory;
import com.richard.fyoung.customerwork.memory.LongTermMemoryProvider;
import com.richard.fyoung.customerwork.observability.TtsHookProvider;
import com.richard.fyoung.customerwork.rag.KnowledgeProvider;
import com.richard.fyoung.customerwork.tool.HigressToolkitConfigurer;
import com.richard.fyoung.customerwork.tool.McpToolkitConfigurer;
import com.richard.fyoung.customerwork.tool.ToolRegistrar;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.recorder.JsonlTraceExporter;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.model.Model;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.storage.InMemoryPlanStorage;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
    private final ContextMemoryFactory contextMemoryFactory;
    private final LongTermMemoryProvider longTermMemoryProvider;
    private final KnowledgeProvider knowledgeProvider;
    private final McpToolkitConfigurer mcpToolkitConfigurer;
    private final HigressToolkitConfigurer higressToolkitConfigurer;
    private final ToolRegistrar toolRegistrar;
    /** 可为 null：未接入 Micrometer 时观测降级为仅日志。 */
    private final MeterRegistry meterRegistry;
    private final TtsHookProvider ttsHookProvider;
    private final NacosPromptService nacosPromptService;

    /** 共享的 trace 导出器（AutoCloseable，进程级单例）。 */
    private volatile JsonlTraceExporter traceExporter;

    public CustomerServiceAgentFactory(Model model,
                                       CustomerWorkProperties properties,
                                       ContextMemoryFactory contextMemoryFactory,
                                       LongTermMemoryProvider longTermMemoryProvider,
                                       KnowledgeProvider knowledgeProvider,
                                       McpToolkitConfigurer mcpToolkitConfigurer,
                                       HigressToolkitConfigurer higressToolkitConfigurer,
                                       ToolRegistrar toolRegistrar,
                                       TtsHookProvider ttsHookProvider,
                                       NacosPromptService nacosPromptService,
                                       ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.model = model;
        this.properties = properties;
        this.contextMemoryFactory = contextMemoryFactory;
        this.longTermMemoryProvider = longTermMemoryProvider;
        this.knowledgeProvider = knowledgeProvider;
        this.mcpToolkitConfigurer = mcpToolkitConfigurer;
        this.higressToolkitConfigurer = higressToolkitConfigurer;
        this.toolRegistrar = toolRegistrar;
        this.ttsHookProvider = ttsHookProvider;
        this.nacosPromptService = nacosPromptService;
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    /** 生效的系统提示词：优先 Nacos 配置中心下发，缺省回退内置提示词。 */
    String systemPrompt() {
        return nacosPromptService.currentPrompt().orElse(SYSTEM_PROMPT);
    }

    /**
     * 构建工具体系：按业务域分组注册工具，可选注册元工具与 MCP 工具。
     */
    Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();

        // 业务工具按域分组注册（壳 + 可替换后端）
        toolRegistrar.registerBusinessTools(toolkit);

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

        Toolkit toolkit = buildToolkit();

        ReActAgent.Builder builder = ReActAgent.builder()
            .name("CustomerServiceAgent-" + sessionId)
            .sysPrompt(systemPrompt())
            .model(model)
            .toolkit(toolkit)
            .memory(contextMemoryFactory.create())        // 短期记忆 / 智能上下文压缩
            .hook(new ObservabilityHook(meterRegistry))   // 可观测采集（日志 + Micrometer 指标）
            .maxIters(properties.getAgent().getMaxIters())
            // 中断后无缝恢复：保留并恢复被打断的待执行工具调用
            .enablePendingToolRecovery(properties.getInterrupt().isPendingToolRecoveryEnabled());

        // Human-in-the-Loop：工具级人工确认闸门
        if (properties.getHumanApproval().isEnabled()) {
            builder.hook(new HumanApprovalHook(
                Set.copyOf(properties.getHumanApproval().getGuardedTools())));
        }

        // 可观测：JSONL trace 导出（数据飞轮采集）
        if (properties.getObservability().isTraceEnabled()) {
            builder.hook(traceExporter());
        }

        // TTS：实时语音合成 Hook（默认关闭）
        ttsHookProvider.create().ifPresent(builder::hook);

        // PlanNotebook：长链路任务规划
        if (properties.getPlan().isEnabled()) {
            builder.planNotebook(PlanNotebook.builder()
                .storage(new InMemoryPlanStorage())
                .maxSubtasks(properties.getPlan().getMaxSubtasks())
                .build());
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
            }
        }

        return builder.build();
    }

    /** 加载技能并注册进 SkillBox（支持 classpath 只读 / filesystem 可写自进化）。 */
    private SkillBox buildSkillBox(Toolkit toolkit) {
        CustomerWorkProperties.Skill cfg = properties.getSkill();
        try {
            List<AgentSkill> skills;
            if ("filesystem".equalsIgnoreCase(cfg.getRepository())) {
                java.nio.file.Path dir = Path.of(cfg.getDirectory());
                java.nio.file.Files.createDirectories(dir);
                skills = new FileSystemSkillRepository(dir, cfg.isWritable()).getAllSkills();
                log.info("[Skill] filesystem 仓库({}, writable={})", dir.toAbsolutePath(), cfg.isWritable());
            } else {
                skills = new ClasspathSkillRepository(cfg.getLocation()).getAllSkills();
            }
            SkillBox skillBox = new SkillBox(toolkit);
            for (AgentSkill skill : skills) {
                skillBox.registerSkill(skill);
            }
            // 运行时加载技能工具：允许 Agent 按需自行加载技能（技能自进化）
            if (cfg.isRuntimeLoadToolEnabled()) {
                skillBox.registerSkillLoadTool();
                log.info("[Skill] 已注册运行时加载技能工具");
            }
            // 代码执行技能：注册读写 / Shell 工具，使技能可执行代码（沙箱内）
            if (cfg.isCodeExecutionEnabled()) {
                skillBox.codeExecution()
                    .workDir(cfg.getCodeExecutionWorkDir())
                    .withRead()
                    .withWrite()
                    .enable();
                log.info("[Skill] 已启用代码执行技能，workDir={}", cfg.getCodeExecutionWorkDir());
            }
            log.info("[Skill] 已加载技能 {} 个: {}", skillBox.getAllSkillIds().size(),
                skillBox.getAllSkillIds());
            return skillBox;
        } catch (Exception e) {
            log.warn("[Skill] 技能加载失败（跳过 Skill 装配）: {}", e.getMessage());
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
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        String delimiter = properties.getMemory().getTenantDelimiter();
        if (delimiter != null && !delimiter.isEmpty()) {
            int idx = sessionId.indexOf(delimiter);
            if (idx > 0) {
                return sessionId.substring(0, idx);
            }
        }
        return sessionId;
    }

    /** 容器关闭时优雅释放 trace 导出器（AutoCloseable）。 */
    @Override
    public void destroy() {
        if (traceExporter != null) {
            try {
                traceExporter.close();
                log.info("[OTEL] trace 导出器已关闭");
            } catch (Exception e) {
                log.warn("[OTEL] 关闭 trace 导出器失败: {}", e.getMessage());
            }
        }
    }
}
