package com.example.customerwork.agent;

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
 * 客服 Agent 工厂（对应流程图③主 Agent 与 ④子 Agent 执行层）。
 *
 * <p>本工厂按会话创建一个装配齐全的 {@link ReActAgent} 实例：</p>
 * <ul>
 *   <li><b>ReAct 推理</b>：模型自主决定调用哪些工具、何时回复（③意图识别→④工具执行→⑤观察循环）；</li>
 *   <li><b>Toolkit 工具体系</b>：注册订单组、售后组、知识库组、人工转接四类工具，
 *       带 @Tool/@ToolParam 注解的方法会被自动提取为 JSON Schema 暴露给模型；</li>
 *   <li><b>Memory</b>：每个会话独立的短期记忆（对应②短期记忆）；</li>
 *   <li><b>Hook</b>：挂载可观测 Hook（对应⑥数据采集）；</li>
 *   <li><b>maxIters</b>：限制 ReAct 最大轮次，避免失控空转。</li>
 * </ul>
 *
 * <p>说明：示例用单一 ReActAgent + 多工具组的方式实现"主从能力"，工具组天然对应不同子能力域。
 * 若要做成图中"主 Agent + 多个独立子 Agent"的进程级编排，可改用 SequentialPipeline /
 * FanoutPipeline 或 1.1+ 的 HarnessAgent 子 Agent 声明，思路一致、扩展点已预留。</p>
 */
@Component
public class CustomerServiceAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceAgentFactory.class);

    private final Model model;

    /** 系统提示词：定义客服人设、路由规则与高风险熔断规则（对应③的路由与熔断逻辑）。 */
    private static final String SYSTEM_PROMPT = """
        你是一名专业、耐心的电商智能客服助手。请遵循以下规则：
        1. 先理解用户意图（咨询 / 订单查询 / 售后退款 / 投诉）。
        2. 咨询类问题优先调用知识库检索工具，并在回答中保留来源标注。
        3. 订单/物流类问题调用订单工具组查询后再回答；可一次并行查询多项。
        4. 退款类问题必须先调用退款资格校验工具，通过后才生成退款工单；
           涉及资金的退款只生成"待人工确认工单"，绝不承诺已直接打款。
        5. 当用户情绪强烈、明确要求人工、投诉升级或涉及大额/高风险时，
           立即调用人工转接工具升级到人工坐席。
        6. 回答简洁、准确、有礼貌；信息不足时主动向用户追问订单号等关键信息。
        """;

    public CustomerServiceAgentFactory(Model model) {
        this.model = model;
    }

    /**
     * 为指定会话创建一个客服 Agent。
     *
     * @param sessionName 会话标识，用于 Agent 命名（生产中与 sessionId 关联，支撑会话恢复）
     * @return 装配完成的 ReActAgent
     */
    public ReActAgent createAgent(String sessionName) {
        log.info("创建客服 Agent，会话: {}", sessionName);

        // 1) 工具体系：注册四类工具组（对应④的各子能力域）
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new KnowledgeBaseTools());   // 咨询/RAG
        toolkit.registerTool(new OrderTools());           // 订单/物流
        toolkit.registerTool(new AfterSalesTools());      // 售后/退款
        toolkit.registerTool(new HumanHandoffTools());    // 人工转接/熔断

        // 2) 构建 ReActAgent
        //    生产中如需长期记忆，追加 .longTermMemory(...).longTermMemoryMode(LongTermMemoryMode.BOTH)
        return ReActAgent.builder()
            .name("CustomerServiceAgent-" + sessionName)
            .sysPrompt(SYSTEM_PROMPT)
            .model(model)
            .toolkit(toolkit)
            .memory(new InMemoryMemory())          // ②短期记忆（会话级）
            .hook(new ObservabilityHook())          // ⑥可观测数据采集
            .maxIters(10)                           // ReAct 最大轮次，防止失控
            .build();
    }
}
