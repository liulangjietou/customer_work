package com.example.customerwork.agent;

import com.example.customerwork.config.CustomerWorkProperties;
import com.example.customerwork.memory.ContextMemoryFactory;
import com.example.customerwork.memory.FactLog;
import com.example.customerwork.memory.InMemoryLongTermMemory;
import com.example.customerwork.memory.LongTermMemoryStore;
import com.example.customerwork.rag.InMemoryKeywordKnowledge;
import com.example.customerwork.tool.AfterSalesTools;
import com.example.customerwork.tool.HumanHandoffTools;
import com.example.customerwork.tool.KnowledgeBaseTools;
import com.example.customerwork.tool.McpToolkitConfigurer;
import com.example.customerwork.tool.OrderTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.recorder.JsonlTraceExporter;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.model.Model;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.storage.InMemoryPlanStorage;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
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
 */
@Component
public class CustomerServiceAgentFactory implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceAgentFactory.class);

    static final String GROUP_KNOWLEDGE = "knowledge";
    static final String GROUP_ORDER = "order";
    static final String GROUP_AFTER_SALES = "after_sales";
    static final String GROUP_HUMAN = "human";

    /** RAG 预置知识：企业政策文档（生产中由真实知识库 / 百炼企业知识库提供）。 */
    private static final List<String> KNOWLEDGE_DOCS = List.of(
        "支持七天无理由退货，商品需保持完好、不影响二次销售；定制类、生鲜类除外。（来源：《售后服务政策》第 3 条）",
        "支持开具电子普通发票与增值税专用发票，可在订单详情页自助申请，1-3 个工作日开具。（来源：《发票管理规则》第 1 条）",
        "单笔订单满 99 元包邮，偏远地区除外；退货运费由责任方承担。（来源：《运费说明》第 2 条）",
        "大额退款（单笔超过 1000 元）需人工坐席复核后处理，预计 1 个工作日内完成。（来源：《资金安全规范》第 5 条）");

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
    private final LongTermMemoryStore longTermMemoryStore;
    private final FactLog factLog;
    private final ContextMemoryFactory contextMemoryFactory;
    private final McpToolkitConfigurer mcpToolkitConfigurer;
    /** 可为 null：未接入 Micrometer 时观测降级为仅日志。 */
    private final MeterRegistry meterRegistry;

    /** 共享的 RAG 知识库（一次灌库，所有会话复用）。 */
    private volatile Knowledge ragKnowledge;
    /** 共享的 trace 导出器（AutoCloseable，进程级单例）。 */
    private volatile JsonlTraceExporter traceExporter;

    public CustomerServiceAgentFactory(Model model,
                                       CustomerWorkProperties properties,
                                       LongTermMemoryStore longTermMemoryStore,
                                       FactLog factLog,
                                       ContextMemoryFactory contextMemoryFactory,
                                       McpToolkitConfigurer mcpToolkitConfigurer,
                                       ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.model = model;
        this.properties = properties;
        this.longTermMemoryStore = longTermMemoryStore;
        this.factLog = factLog;
        this.contextMemoryFactory = contextMemoryFactory;
        this.mcpToolkitConfigurer = mcpToolkitConfigurer;
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    /**
     * 构建工具体系：按业务域分组注册工具，可选注册元工具与 MCP 工具。
     */
    Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();

        toolkit.createToolGroup(GROUP_KNOWLEDGE, "知识库检索：产品政策、售后规则、发票运费等 FAQ", true);
        toolkit.createToolGroup(GROUP_ORDER, "订单与物流查询", true);
        toolkit.createToolGroup(GROUP_AFTER_SALES, "售后与退款（涉资金走人工确认）", true);
        toolkit.createToolGroup(GROUP_HUMAN, "人工坐席转接与风险熔断", true);

        toolkit.registration().tool(new KnowledgeBaseTools()).group(GROUP_KNOWLEDGE).apply();
        toolkit.registration().tool(new OrderTools()).group(GROUP_ORDER).apply();
        toolkit.registration().tool(new AfterSalesTools()).group(GROUP_AFTER_SALES).apply();
        toolkit.registration().tool(new HumanHandoffTools()).group(GROUP_HUMAN).apply();

        if (properties.getAgent().isMetaToolEnabled()) {
            toolkit.registerMetaTool();
            log.info("已启用 Meta-Tool（元工具）");
        }

        // MCP：把存量 HTTP 系统接成 Agent 工具（默认关闭）
        mcpToolkitConfigurer.configure(toolkit);

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
            .sysPrompt(SYSTEM_PROMPT)
            .model(model)
            .toolkit(toolkit)
            .memory(contextMemoryFactory.create())        // 短期记忆 / 智能上下文压缩
            .hook(new ObservabilityHook(meterRegistry))   // 可观测采集（日志 + Micrometer 指标）
            .maxIters(properties.getAgent().getMaxIters());

        // Human-in-the-Loop：工具级人工确认闸门
        if (properties.getHumanApproval().isEnabled()) {
            builder.hook(new HumanApprovalHook(
                Set.copyOf(properties.getHumanApproval().getGuardedTools())));
        }

        // 可观测：JSONL trace 导出（数据飞轮采集）
        if (properties.getObservability().isTraceEnabled()) {
            builder.hook(traceExporter());
        }

        // PlanNotebook：长链路任务规划
        if (properties.getPlan().isEnabled()) {
            builder.planNotebook(PlanNotebook.builder()
                .storage(new InMemoryPlanStorage())
                .maxSubtasks(properties.getPlan().getMaxSubtasks())
                .build());
        }

        // 多租户长期记忆
        if (properties.getMemory().isLongTermEnabled()) {
            String tenantId = resolveTenant(sessionId);
            builder.longTermMemory(new InMemoryLongTermMemory(
                    longTermMemoryStore, factLog, tenantId, properties.getMemory().getRetrieveTopK()))
                .longTermMemoryMode(LongTermMemoryMode.BOTH);
        }

        // RAG 知识检索
        if (properties.getRag().isEnabled()) {
            builder.knowledge(ragKnowledge()).ragMode(RAGMode.AGENTIC);
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

    /** 懒加载并灌入预置文档的共享 RAG 知识库。 */
    private Knowledge ragKnowledge() {
        if (ragKnowledge == null) {
            synchronized (this) {
                if (ragKnowledge == null) {
                    InMemoryKeywordKnowledge knowledge =
                        new InMemoryKeywordKnowledge(properties.getRag().getTopK());
                    knowledge.addTexts(KNOWLEDGE_DOCS).block();
                    ragKnowledge = knowledge;
                    log.info("[RAG] 知识库已就绪，预置文档 {} 条", KNOWLEDGE_DOCS.size());
                }
            }
        }
        return ragKnowledge;
    }

    /** 从 classpath 加载 Markdown 技能并注册进 SkillBox。 */
    private SkillBox buildSkillBox(Toolkit toolkit) {
        try {
            ClasspathSkillRepository repo =
                new ClasspathSkillRepository(properties.getSkill().getLocation());
            SkillBox skillBox = new SkillBox(toolkit);
            for (AgentSkill skill : repo.getAllSkills()) {
                skillBox.registerSkill(skill);
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
