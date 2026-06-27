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
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 多 Agent 编排器（对应「多 Agent 与分布式协作」：并行 / 串行 + 主从分层，AgentScope 2.0 迁移版）。
 *
 * <p>1.x 的 {@code Pipelines} 在 2.0 已移除；本类改用 Reactor 直接编排三个专职专家 Agent
 * （订单 / 售后 / 知识库），同一批专家也被 {@code HarnessAgentFactory} 注册为主智能体的 subagent：</p>
 * <ul>
 *   <li><b>fanout（并行）</b>：把同一问题<b>真并发</b>分发给所有专家——每个 {@code agent.call} 经
 *       {@code subscribeOn(boundedElastic)} 挪到独立线程，即使底层模型调用是阻塞式也能并行；
 *       受 {@code maxConcurrency} 限流，单专家 {@code timeoutSeconds} 超时与异常均被隔离成占位结果，
 *       不拖垮整体，最后聚合各自结论；</li>
 *   <li><b>sequential（串行）</b>：让问题依次流过各专家逐步细化（{@code Mono} 链式）。</li>
 * </ul>
 *
 * <p>说明：HarnessAgent 的 subagent 由主智能体在 ReAct 循环里自行逐个 spawn，<b>本质串行且不可编程控制</b>；
 * 需要"主 + 子智能体"<b>可控并行</b>时走本编排器（它构造的正是注册为 subagent 的那批专家），
 * 跨进程则用 A2A + Nacos 注册发现。</p>
 */
@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    /** 编排模式常量（避免魔法值）。 */
    private static final String MODE_SEQUENTIAL = "sequential";
    /** 专家并行调用失败错误码。 */
    private static final String ERR_EXPERT_FAIL = "MAS-EXPERT-FAIL";

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
        Msg msg = userMsg(userText);

        if (MODE_SEQUENTIAL.equalsIgnoreCase(properties.getMultiAgent().getMode())) {
            log.info("multi-agent orchestration: sequential, {} specialists", specialists.size());
            return sequential(specialists, msg).map(Msg::getTextContent);
        }
        CustomerWorkProperties.MultiAgent cfg = properties.getMultiAgent();
        log.info("multi-agent orchestration: parallel fanout, {} specialists, maxConcurrency={}, timeout={}s",
            specialists.size(), cfg.getMaxConcurrency(), cfg.getTimeoutSeconds());
        List<Mono<Msg>> tasks = specialists.stream()
            .map(agent -> callExpert(agent, msg))
            .collect(Collectors.toList());
        return fanout(tasks, cfg.getMaxConcurrency()).map(this::aggregate);
    }

    /**
     * 真并行扇出组合子：每个任务经 {@code subscribeOn(boundedElastic)} 在独立线程上并发执行
     * （即便底层是阻塞调用也能真并行），受 {@code concurrency} 限流，按完成先后收集。
     *
     * <p>包级可见，便于单测用可控的阻塞 {@code Mono} 直接验证并发度。</p>
     */
    Mono<List<Msg>> fanout(List<Mono<Msg>> tasks, int concurrency) {
        int limit = Math.max(1, concurrency);
        return Flux.fromIterable(tasks)
            .flatMap(task -> task.subscribeOn(Schedulers.boundedElastic()), limit)
            .collectList();
    }

    /** 单专家调用：加超时与错误隔离，失败/超时降级为带专家名的占位结果，避免拖垮整体并行。 */
    private Mono<Msg> callExpert(ReActAgent agent, Msg msg) {
        String name = agent.getName();
        return agent.call(List.of(msg), CONSULT_CTX)
            .timeout(Duration.ofSeconds(properties.getMultiAgent().getTimeoutSeconds()))
            .onErrorResume(err -> {
                log.error("expert call failed in parallel fanout, errorCode={}, expert={}", ERR_EXPERT_FAIL, name, err);
                return Mono.just(errorPlaceholder(name));
            });
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

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }

    /** 专家失败/超时的占位消息（保留专家名，便于聚合时标注哪位专家暂不可用）。 */
    private Msg errorPlaceholder(String expertName) {
        return Msg.builder().role(MsgRole.ASSISTANT).name(expertName)
            .content(TextBlock.builder().text("[暂不可用，请稍后重试或转交人工]").build()).build();
    }
}
