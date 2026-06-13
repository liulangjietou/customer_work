package com.example.customerwork.agent;

import com.example.customerwork.config.CustomerWorkProperties;
import com.example.customerwork.tool.AfterSalesTools;
import com.example.customerwork.tool.HumanHandoffTools;
import com.example.customerwork.tool.KnowledgeBaseTools;
import com.example.customerwork.tool.OrderTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 客服 Agent 工厂（对应深度解析一文③主 Agent 与 ④子 Agent 执行层）。
 *
 * <p>按会话装配一个 {@link ReActAgent}：</p>
 * <ul>
 *   <li><b>ReAct 推理</b>：模型自主决定调用哪些工具、何时回复（意图识别→工具执行→观察循环）；</li>
 *   <li><b>Toolkit + Tool Group</b>：把工具按业务域分组（知识库 / 订单 / 售后 / 人工），
 *       带 {@code @Tool} / {@code @ToolParam} 注解的方法被自动提取为 JSON Schema 暴露给模型；</li>
 *   <li><b>Memory</b>：每会话独立短期记忆；</li>
 *   <li><b>Hook</b>：挂载可观测 Hook（数据飞轮采集起点）；</li>
 *   <li><b>maxIters</b>：限制 ReAct 最大轮次，避免失控空转。</li>
 * </ul>
 */
@Component
public class CustomerServiceAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceAgentFactory.class);

    /** 工具组名：按业务域划分，便于管理与（未来）按需激活 / 元工具调度。 */
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
        """;

    private final Model model;
    private final CustomerWorkProperties properties;

    public CustomerServiceAgentFactory(Model model, CustomerWorkProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    /**
     * 构建工具体系：按业务域分组注册四类工具。
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

        return toolkit;
    }

    /**
     * 为指定会话创建一个客服 Agent。
     *
     * @param sessionId 会话标识（与持久化 key 关联，支撑会话恢复）
     * @return 装配完成的 ReActAgent
     */
    public ReActAgent createAgent(String sessionId) {
        log.info("创建客服 Agent，会话: {}", sessionId);

        return ReActAgent.builder()
            .name("CustomerServiceAgent-" + sessionId)
            .sysPrompt(SYSTEM_PROMPT)
            .model(model)
            .toolkit(buildToolkit())
            .memory(new InMemoryMemory())
            .hook(new ObservabilityHook())
            .maxIters(properties.getAgent().getMaxIters())
            .build();
    }
}
