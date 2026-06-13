package com.example.customerwork.agent;

import com.example.customerwork.config.CustomerWorkProperties;
import com.example.customerwork.memory.InMemoryLongTermMemory;
import com.example.customerwork.memory.LongTermMemoryStore;
import com.example.customerwork.tool.AfterSalesTools;
import com.example.customerwork.tool.HumanHandoffTools;
import com.example.customerwork.tool.KnowledgeBaseTools;
import com.example.customerwork.tool.OrderTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.model.Model;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.storage.InMemoryPlanStorage;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 客服 Agent 工厂（对应深度解析一文③主 Agent 与 ④子 Agent 执行层）。
 *
 * <p>按会话装配一个 {@link ReActAgent}，覆盖文档第三章的多个关键组件：</p>
 * <ul>
 *   <li><b>ReAct 推理</b>（3.1）：模型自主决定调用哪些工具、何时回复；</li>
 *   <li><b>Toolkit + Tool Group + Meta-Tool</b>（3.2）：工具按业务域分组，可选启用元工具运行时调度；</li>
 *   <li><b>PlanNotebook</b>（3.3）：长链路任务规划；</li>
 *   <li><b>短期 Memory + 多租户长期记忆</b>（3.4）：会话级短期记忆 + 跨会话、按租户隔离的长期记忆；</li>
 *   <li><b>Hook</b>（3.6/⑥）：可观测数据采集；</li>
 *   <li><b>maxIters</b>：限制 ReAct 最大轮次，避免失控空转。</li>
 * </ul>
 */
@Component
public class CustomerServiceAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceAgentFactory.class);

    /** 工具组名：按业务域划分，便于管理与（开启元工具后）按需激活。 */
    static final String GROUP_KNOWLEDGE = "knowledge";
    static final String GROUP_ORDER = "order";
    static final String GROUP_AFTER_SALES = "after_sales";
    static final String GROUP_HUMAN = "human";

    /** 系统提示词：定义客服人设、路由规则与高风险熔断规则。 */
    private static final String SYSTEM_PROMPT = """
        你是一名专业、耐心的电商智能客服助手。请严格遵循以下规则：
        1. 先理解用户意图（咨询 / 订单查询 / 售后退款 / 投诉）。
        2. 咨询类问题优先调用知识库检索工具，并在回答中保留来源标注。
        3. 订单 / 物流类问题调用订单工具组查询后再回答，可一次并行查询多项。
        4. 退款类问题必须先调用退款资格校验工具，通过后才生成退款工单；
           涉及资金的退款只生成"待人工确认工单"，绝不承诺已直接打款。
        5. 当用户情绪强烈、明确要求人工、投诉升级或涉及大额 / 高风险时，
           立即调用人工转接工具升级到人工坐席。
        6. 回答简洁、准确、有礼貌；信息不足时主动向用户追问订单号等关键信息。
        7. 不得编造订单、物流、政策等事实；工具查不到就如实说明并引导用户。
        8. 涉及多步骤的复杂任务，可借助计划工具拆解为子任务并按序推进。
        """;

    private final Model model;
    private final CustomerWorkProperties properties;
    private final LongTermMemoryStore longTermMemoryStore;

    public CustomerServiceAgentFactory(Model model,
                                       CustomerWorkProperties properties,
                                       LongTermMemoryStore longTermMemoryStore) {
        this.model = model;
        this.properties = properties;
        this.longTermMemoryStore = longTermMemoryStore;
    }

    /**
     * 构建工具体系：按业务域分组注册四类工具，可选注册元工具。
     *
     * <p>抽成独立方法便于单测在不启动模型 / Spring 上下文的情况下校验工具注册是否完整。</p>
     */
    Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();

        // 创建业务域工具组（active=true：默认对模型可见、可调用）
        toolkit.createToolGroup(GROUP_KNOWLEDGE, "知识库检索：产品政策、售后规则、发票运费等 FAQ", true);
        toolkit.createToolGroup(GROUP_ORDER, "订单与物流查询", true);
        toolkit.createToolGroup(GROUP_AFTER_SALES, "售后与退款（涉资金走人工确认）", true);
        toolkit.createToolGroup(GROUP_HUMAN, "人工坐席转接与风险熔断", true);

        toolkit.registration().tool(new KnowledgeBaseTools()).group(GROUP_KNOWLEDGE).apply();
        toolkit.registration().tool(new OrderTools()).group(GROUP_ORDER).apply();
        toolkit.registration().tool(new AfterSalesTools()).group(GROUP_AFTER_SALES).apply();
        toolkit.registration().tool(new HumanHandoffTools()).group(GROUP_HUMAN).apply();

        // 元工具：允许 Agent 在运行时自主启停工具组，缓解上下文窗口压力（默认关闭）
        if (properties.getAgent().isMetaToolEnabled()) {
            toolkit.registerMetaTool();
            log.info("已启用 Meta-Tool（元工具），Agent 可运行时管理工具组");
        }

        return toolkit;
    }

    /**
     * 为指定会话创建一个客服 Agent。
     *
     * @param sessionId 会话标识（与持久化 key 关联，支撑会话恢复；可含租户前缀如 tenantA:conv-1）
     * @return 装配完成的 ReActAgent
     */
    public ReActAgent createAgent(String sessionId) {
        log.info("创建客服 Agent，会话: {}", sessionId);

        ReActAgent.Builder builder = ReActAgent.builder()
            .name("CustomerServiceAgent-" + sessionId)
            .sysPrompt(SYSTEM_PROMPT)
            .model(model)
            .toolkit(buildToolkit())
            .memory(new InMemoryMemory())
            .hook(new ObservabilityHook())
            .maxIters(properties.getAgent().getMaxIters());

        // PlanNotebook：长链路任务规划（3.3）
        if (properties.getPlan().isEnabled()) {
            builder.planNotebook(PlanNotebook.builder()
                .storage(new InMemoryPlanStorage())
                .maxSubtasks(properties.getPlan().getMaxSubtasks())
                .build());
        }

        // 多租户长期记忆：跨会话、按租户隔离（3.4）
        if (properties.getMemory().isLongTermEnabled()) {
            String tenantId = resolveTenant(sessionId);
            builder.longTermMemory(new InMemoryLongTermMemory(
                    longTermMemoryStore, tenantId, properties.getMemory().getRetrieveTopK()))
                .longTermMemoryMode(LongTermMemoryMode.BOTH);
            log.debug("会话 {} 绑定长期记忆租户: {}", sessionId, tenantId);
        }

        return builder.build();
    }

    /**
     * 从 sessionId 解析租户 ID：sessionId 形如 {@code tenantA:conv-1} 时取分隔符前的部分，
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
}
