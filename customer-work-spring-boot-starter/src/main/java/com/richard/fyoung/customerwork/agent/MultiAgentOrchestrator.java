package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.AfterSalesTools;
import com.richard.fyoung.customerwork.tool.KnowledgeBaseTools;
import com.richard.fyoung.customerwork.tool.OrderTools;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 多 Agent 编排器（对应「多 Agent 与分布式协作」：串/并行 + 主从分层，AgentScope 2.0 迁移版）。
 *
 * <p>1.x 的 {@code Pipelines} 在 2.0 已移除；本类改用 Reactor 直接编排三个专职专家 Agent
 * （订单 / 售后 / 知识库）：</p>
 * <ul>
 *   <li><b>fanout</b>：把同一问题并行分发给所有专家（{@code Flux.flatMap}），聚合各自结论；</li>
 *   <li><b>sequential</b>：让问题依次流过各专家逐步细化（{@code Mono} 链式）。</li>
 * </ul>
 *
 * <p>这是文档「主从分层」模式的进程内实现；2.0 也可用 HarnessAgent 的 Subagent 能力做更强的
 * 子智能体编排（见 {@code SubagentOrchestrator}），跨进程则用 A2A + Nacos 注册发现。</p>
 */
@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final Model model;
    private final CustomerWorkProperties properties;
    private final OrderBackend orderBackend;
    private final AfterSalesBackend afterSalesBackend;
    private final KnowledgeBackend knowledgeBackend;

    public MultiAgentOrchestrator(Model model,
                                  CustomerWorkProperties properties,
                                  OrderBackend orderBackend,
                                  AfterSalesBackend afterSalesBackend,
                                  KnowledgeBackend knowledgeBackend) {
        this.model = model;
        this.properties = properties;
        this.orderBackend = orderBackend;
        this.afterSalesBackend = afterSalesBackend;
        this.knowledgeBackend = knowledgeBackend;
    }

    private static final RuntimeContext CONSULT_CTX = RuntimeContext.builder()
        .userId("multi-agent").sessionId("consult").build();

    /** 构建三个专职专家 Agent。 */
    public List<ReActAgent> buildSpecialists() {
        return List.of(
            specialist("OrderExpert",
                "你是订单与物流专家。只就订单状态、物流轨迹、金额等问题作答，调用订单工具查询后回答；与你无关的问题简要说明并建议转交对应专家。",
                new OrderTools(orderBackend)),
            specialist("AfterSalesExpert",
                "你是售后与退款专家。处理退款资格校验与退款工单；涉及资金只生成待人工确认工单，绝不承诺已打款。",
                new AfterSalesTools(afterSalesBackend)),
            specialist("KnowledgeExpert",
                "你是政策咨询专家。依据知识库回答退换货、发票、运费等政策问题，并保留来源标注。",
                new KnowledgeBaseTools(knowledgeBackend)));
    }

    private ReActAgent specialist(String name, String prompt, Object tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        return ReActAgent.builder()
            .name(name)
            .sysPrompt(prompt)
            .model(model)
            .toolkit(toolkit)
            .maxIters(properties.getMultiAgent().getMaxIters())
            .build();
    }

    /**
     * 多专家协作处理一个问题。
     *
     * @return 聚合后的回复（fanout 模式拼接各专家结论；sequential 模式取最终结论）
     */
    public Mono<String> consult(String userText) {
        List<ReActAgent> specialists = buildSpecialists();
        Msg msg = Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(userText).build()).build();

        if ("sequential".equalsIgnoreCase(properties.getMultiAgent().getMode())) {
            log.info("multi-agent orchestration: sequential, {} specialists", specialists.size());
            return sequential(specialists, msg).map(Msg::getTextContent);
        }
        log.info("multi-agent orchestration: fanout, {} specialists", specialists.size());
        return Flux.fromIterable(specialists)
            .flatMap(agent -> agent.call(List.of(msg), CONSULT_CTX))
            .collectList()
            .map(this::aggregate);
    }

    /** 串行编排：问题依次流过各专家，后一个以前一个的回复为输入逐步细化，取最终结论。 */
    private Mono<Msg> sequential(List<ReActAgent> specialists, Msg input) {
        Mono<Msg> chain = Mono.just(input);
        for (ReActAgent agent : specialists) {
            chain = chain.flatMap(prev -> agent.call(List.of(prev), CONSULT_CTX));
        }
        return chain;
    }

    /** 聚合 fanout 各专家回复。 */
    String aggregate(List<Msg> replies) {
        return replies.stream()
            .map(m -> "【" + m.getName() + "】" + m.getTextContent())
            .collect(Collectors.joining("\n\n"));
    }
}
