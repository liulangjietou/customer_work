package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.dto.IntentResult;
import com.richard.fyoung.customerwork.tool.AfterSalesTools;
import com.richard.fyoung.customerwork.tool.DefaultActiveGroupsToolkit;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 多 Agent 编排器（对应「多 Agent 与分布式协作」：智能路由 + 并行 / 串行 + reduce 归纳，AgentScope 2.0 迁移版）。
 *
 * <p>1.x 的 {@code Pipelines} 在 2.0 已移除；本类改用 Reactor 直接编排三个专职专家 Agent
 * （订单 / 售后 / 知识库），同一批专家也被 {@code HarnessAgentFactory} 注册为主智能体的 subagent。
 * 一次 {@link #consult} 的 fanout 链路为 <b>路由 → 并行会诊 → 归纳</b>：</p>
 * <ol>
 *   <li><b>智能路由</b>（{@code routingEnabled}）：先用轻量分诊器判断意图，只把问题发给<b>相关</b>专家，
 *       省 token、更准；分诊失败或 other 意图则广播全部，保证不漏；</li>
 *   <li><b>真并行 fanout</b>：每个 {@code agent.call} 经 {@code subscribeOn(boundedElastic)} 挪到独立线程，
 *       即便底层模型调用是阻塞式也能真并发；受 {@code maxConcurrency} 限流，单专家 {@code timeoutSeconds}
 *       超时与异常均被隔离成占位结果，不拖垮整体；</li>
 *   <li><b>reduce 归纳</b>（{@code reduceEnabled}）：用归纳器把多专家结论二次合成<b>统一口径</b>的简洁回复
 *       （去重、消解冲突），而非简单拼接；单专家或关闭时退化为拼接。</li>
 * </ol>
 *
 * <p><b>sequential（串行）</b>模式则让问题依次流过各专家逐步细化（{@code Mono} 链式）。</p>
 *
 * <p>说明：HarnessAgent 的 subagent 由主智能体在 ReAct 循环里自行逐个 spawn，<b>本质串行且不可编程控制</b>；
 * 需要"主 + 子智能体"<b>可控并行</b>时走本编排器，跨进程则用 A2A + Nacos 注册发现。</p>
 */
@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    /** 编排模式常量（避免魔法值）。 */
    private static final String MODE_SEQUENTIAL = "sequential";
    /** 专家名常量（路由映射与装配共用，避免魔法值）。 */
    private static final String EXPERT_ORDER = "OrderExpert";
    private static final String EXPERT_AFTERSALES = "AfterSalesExpert";
    private static final String EXPERT_KNOWLEDGE = "KnowledgeExpert";
    /** 错误码。 */
    private static final String ERR_EXPERT_FAIL = "MAS-EXPERT-FAIL";
    private static final String ERR_ROUTE_FAIL = "MAS-ROUTE-FAIL";
    private static final String ERR_REDUCE_FAIL = "MAS-REDUCE-FAIL";

    /**
     * 规则快车道关键词表（intent → 触发词）。命中<b>唯一</b>意图时直路由、跳过 LLM 分诊；
     * 命中多类或无命中则交慢车道（LLM）。借鉴 AliGo「快慢车道」：规则保确定性、LLM 保灵活性。
     * 用 {@link LinkedHashMap} 固定遍历序，避免魔法值散落。
     */
    private static final Map<String, List<String>> FAST_ROUTE_KEYWORDS = new LinkedHashMap<>();

    static {
        FAST_ROUTE_KEYWORDS.put("refund", List.of("退款", "退货", "退钱", "申请退", "已读不退"));
        FAST_ROUTE_KEYWORDS.put("order", List.of("物流", "快递", "到哪了", "发货", "签收", "运单", "几天到"));
        FAST_ROUTE_KEYWORDS.put("complaint", List.of("投诉", "差评", "举报", "态度", "315", "曝光"));
        FAST_ROUTE_KEYWORDS.put("consult", List.of("发票", "运费", "政策", "几天无理由", "保修", "能不能开票"));
    }

    /** 可观测指标名（并行编排健康度，经 Micrometer 暴露到 /actuator/prometheus）。 */
    private static final String M_ROUTE = "customerwork.mas.route";
    private static final String M_FANOUT_EXPERTS = "customerwork.mas.fanout.experts";
    private static final String M_EXPERT = "customerwork.mas.expert";
    private static final String M_REDUCE = "customerwork.mas.reduce";

    private final Model model;
    private final CustomerWorkProperties properties;
    private final OrderBackend orderBackend;
    private final AfterSalesBackend afterSalesBackend;
    private final KnowledgeBackend knowledgeBackend;
    /** 可为 null：未接入 Micrometer 时降级为无指标（仅日志）。 */
    private MeterRegistry meterRegistry;

    /**
     * 专家 Agent 缓存（P3：避免每次 consult 都重建 Agent）。
     * Agent 无状态（状态由 StateStore 按 sessionId 持久化），可安全复用。
     */
    private volatile List<ReActAgent> cachedSpecialists;

    /** Spring 注入构造：MeterRegistry 经 ObjectProvider 可选注入（actuator 缺席时降级）。 */
    @Autowired
    public MultiAgentOrchestrator(Model model,
                                  CustomerWorkProperties properties,
                                  OrderBackend orderBackend,
                                  AfterSalesBackend afterSalesBackend,
                                  KnowledgeBackend knowledgeBackend,
                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(model, properties, orderBackend, afterSalesBackend, knowledgeBackend);
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /** 无指标构造（单测 / 未接入 Micrometer 场景）。 */
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

    /** 测试可注入指标注册表（避免暴露 setter 给生产链路误用）。 */
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private static final RuntimeContext CONSULT_CTX = RuntimeContext.builder()
        .userId("multi-agent").sessionId("consult").build();
    private static final RuntimeContext ROUTER_CTX = RuntimeContext.builder()
        .userId("multi-agent").sessionId("router").build();
    private static final RuntimeContext REDUCER_CTX = RuntimeContext.builder()
        .userId("multi-agent").sessionId("reducer").build();

    /**
     * 构建三个专职专家 Agent（带缓存：首次构建后复用，避免每次 consult 重建 Agent）。
     * Agent 无状态（状态由 StateStore 按 sessionId 持久化），可安全复用。
     */
    public List<ReActAgent> buildSpecialists() {
        if (cachedSpecialists != null) {
            return cachedSpecialists;
        }
        synchronized (this) {
            if (cachedSpecialists != null) {
                return cachedSpecialists;
            }
            List<ReActAgent> specialists = List.of(
                specialist(EXPERT_ORDER,
                    "你是订单与物流专家。只就订单状态、物流轨迹、金额等问题作答，调用订单工具查询后回答；与你无关的问题简要说明并建议转交对应专家。",
                    new OrderTools(orderBackend)),
                specialist(EXPERT_AFTERSALES,
                    "你是售后与退款专家。处理退款资格校验与退款工单；涉及资金只生成待人工确认工单，绝不承诺已打款。",
                    new AfterSalesTools(afterSalesBackend)),
                specialist(EXPERT_KNOWLEDGE,
                    "你是政策咨询专家。依据知识库回答退换货、发票、运费等政策问题，并保留来源标注。",
                    new KnowledgeBaseTools(knowledgeBackend)));
            cachedSpecialists = specialists;
            log.info("[MultiAgent] specialists cached: {} experts", specialists.size());
            return specialists;
        }
    }

    /** 清除专家缓存（测试 / 热更新场景使用）。 */
    public void clearSpecialistCache() {
        cachedSpecialists = null;
        log.info("[MultiAgent] specialist cache cleared");
    }

    private ReActAgent specialist(String name, String prompt, Object tool) {
        Toolkit toolkit = new DefaultActiveGroupsToolkit();
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
     * @return 聚合后的回复（fanout 模式经路由 + 并行 + 归纳；sequential 模式取最终结论）
     */
    public Mono<String> consult(String userText) {
        List<ReActAgent> all = buildSpecialists();
        Msg msg = userMsg(userText);
        CustomerWorkProperties.MultiAgent cfg = properties.getMultiAgent();

        if (MODE_SEQUENTIAL.equalsIgnoreCase(cfg.getMode())) {
            log.info("multi-agent orchestration: sequential, {} specialists", all.size());
            return sequential(all, msg).map(Msg::getTextContent);
        }
        return selectExperts(userText, all)
            .flatMap(experts -> {
                log.info("multi-agent orchestration: parallel fanout, {} experts (routed), maxConcurrency={}, timeout={}s",
                    experts.size(), cfg.getMaxConcurrency(), cfg.getTimeoutSeconds());
                List<Mono<Msg>> tasks = experts.stream()
                    .map(agent -> callExpert(agent, msg))
                    .collect(Collectors.toList());
                return fanout(tasks, cfg.getMaxConcurrency());
            })
            .flatMap(replies -> reduce(userText, replies));
    }

    /**
     * 智能路由：开启时用轻量分诊器判断意图，只挑相关专家；关闭 / 分诊失败时返回全部专家。
     *
     * <p>包级可见，便于单测以关闭路径离线断言退化为全部专家。</p>
     */
    Mono<List<ReActAgent>> selectExperts(String userText, List<ReActAgent> all) {
        CustomerWorkProperties.MultiAgent cfg = properties.getMultiAgent();
        if (!cfg.isRoutingEnabled()) {
            recordRoute("disabled", all.size());
            return Mono.just(all);
        }
        // 快车道：规则命中唯一意图则直路由，跳过 LLM 分诊（省一次模型调用、提准降延迟）
        if (cfg.isFastRouteEnabled()) {
            Optional<String> fast = fastRouteIntent(userText);
            if (fast.isPresent()) {
                List<ReActAgent> picked = expertsForIntent(fast.get(), all);
                log.info("multi-agent routing(fast-lane): intent={}, picked={}", fast.get(),
                    picked.stream().map(ReActAgent::getName).collect(Collectors.toList()));
                recordRoute("fast:" + fast.get(), picked.size());
                return Mono.just(picked);
            }
        }
        // 慢车道：交 LLM 分诊
        return routerAgent().call("判断用户意图并结构化输出：" + userText, IntentResult.class, ROUTER_CTX)
            .map(m -> m.getStructuredData(IntentResult.class))
            .map(intent -> {
                List<ReActAgent> picked = expertsForIntent(intent.intent(), all);
                log.info("multi-agent routing: intent={}, picked={}", intent.intent(),
                    picked.stream().map(ReActAgent::getName).collect(Collectors.toList()));
                recordRoute(intent.intent(), picked.size());
                return picked;
            })
            .onErrorResume(err -> {
                log.error("multi-agent routing failed, fallback to all experts, errorCode={}", ERR_ROUTE_FAIL, err);
                recordRoute("fallback", all.size());
                return Mono.just(all);
            });
    }

    /**
     * 意图 → 专家子集的纯映射（可离线确定性单测）：
     * order→订单专家，refund/complaint→售后专家，consult→知识库专家，其余（含 other/未知）→全部专家。
     */
    List<ReActAgent> expertsForIntent(String intent, List<ReActAgent> all) {
        String key = intent == null ? "" : intent.trim().toLowerCase();
        Set<String> names;
        switch (key) {
            case "order":
                names = Set.of(EXPERT_ORDER);
                break;
            case "refund":
            case "complaint":
                names = Set.of(EXPERT_AFTERSALES);
                break;
            case "consult":
                names = Set.of(EXPERT_KNOWLEDGE);
                break;
            default:
                return all;
        }
        List<ReActAgent> picked = all.stream()
            .filter(a -> names.contains(a.getName()))
            .collect(Collectors.toList());
        return picked.isEmpty() ? all : picked;
    }

    /**
     * 规则快车道：用关键词表判定意图。仅当命中<b>唯一</b>意图时返回该意图（直路由）；
     * 命中多类（语义可能跨域）或无命中时返回 {@code empty}，交 LLM 慢车道兜底。
     *
     * <p>纯函数，可离线确定性单测，亦供意图评测框架（{@code eval} 包）复用。</p>
     */
    public Optional<String> fastRouteIntent(String userText) {
        if (userText == null || userText.trim().isEmpty()) {
            return Optional.empty();
        }
        String hit = null;
        for (Map.Entry<String, List<String>> e : FAST_ROUTE_KEYWORDS.entrySet()) {
            boolean matched = e.getValue().stream().anyMatch(userText::contains);
            if (matched) {
                if (hit != null) {
                    return Optional.empty();   // 命中多类意图，语义模糊 → 交 LLM
                }
                hit = e.getKey();
            }
        }
        return Optional.ofNullable(hit);
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

    /**
     * reduce 归纳：开启且多于一个专家时，用归纳器把各专家结论合成统一口径回复；否则退化为拼接。
     *
     * <p>包级可见，便于单测以关闭 / 单专家路径离线断言退化为拼接。</p>
     */
    Mono<String> reduce(String userText, List<Msg> replies) {
        String joined = aggregate(replies);
        if (!properties.getMultiAgent().isReduceEnabled() || replies.size() <= 1) {
            recordReduce(false);
            return Mono.just(joined);
        }
        recordReduce(true);
        String prompt = "用户问题：" + userText + "\n\n以下是各专家的结论，请综合成一段面向用户的、统一口径的简洁回复，"
            + "去重并消解冲突，不要罗列专家名：\n\n" + joined;
        return reducerAgent().call(prompt, REDUCER_CTX)
            .map(Msg::getTextContent)
            .onErrorResume(err -> {
                log.error("multi-agent reduce failed, fallback to aggregated replies, errorCode={}", ERR_REDUCE_FAIL, err);
                return Mono.just(joined);
            });
    }

    /** 单专家调用：加超时与错误隔离，并按 expert/outcome 维度记录耗时指标，失败/超时降级为占位。 */
    private Mono<Msg> callExpert(ReActAgent agent, Msg msg) {
        String name = agent.getName();
        AtomicLong start = new AtomicLong();
        return agent.call(List.of(msg), CONSULT_CTX)
            .doOnSubscribe(s -> start.set(System.nanoTime()))
            .timeout(Duration.ofSeconds(properties.getMultiAgent().getTimeoutSeconds()))
            .doOnNext(r -> recordExpert(name, "success", System.nanoTime() - start.get()))
            .onErrorResume(err -> {
                String outcome = err instanceof TimeoutException ? "timeout" : "failure";
                recordExpert(name, outcome, System.nanoTime() - start.get());
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

    /** 聚合 fanout 各专家回复（reduce 关闭 / 单专家时的回退展现）。 */
    String aggregate(List<Msg> replies) {
        return replies.stream()
            .map(m -> "【" + m.getName() + "】" + m.getTextContent())
            .collect(Collectors.joining("\n\n"));
    }

    /** 轻量分诊器：无业务工具、结构化输出意图，单轮即可。 */
    private ReActAgent routerAgent() {
        return ReActAgent.builder()
            .name("RouterAgent")
            .sysPrompt("你是客服分诊器。判断用户消息意图并归类为：order（订单/物流）、refund（退款/售后）、"
                + "complaint（投诉）、consult（政策咨询）、other。只输出结构化结果，不要调用工具。")
            .model(model)
            .toolkit(new Toolkit())
            .maxIters(1)
            .build();
    }

    /** 归纳器：无业务工具，把多专家结论合成统一口径回复。 */
    private ReActAgent reducerAgent() {
        return ReActAgent.builder()
            .name("ReducerAgent")
            .sysPrompt("你是客服回复归纳器。把多位专家的结论综合成一段面向用户的、统一口径的简洁中文回复："
                + "去重、消解冲突、保留关键事实与来源，不要罗列专家名，不要调用工具。")
            .model(model)
            .toolkit(new Toolkit())
            .maxIters(1)
            .build();
    }

    /** 记录路由结果：意图分布 + 本次 fanout 实际投递的专家数。 */
    void recordRoute(String intent, int expertCount) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(M_ROUTE, "intent", intent == null ? "unknown" : intent).increment();
        meterRegistry.summary(M_FANOUT_EXPERTS).record(expertCount);
    }

    /** 记录单专家调用耗时（按 expert + outcome=success/timeout/failure 维度）。 */
    void recordExpert(String expert, String outcome, long nanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer(M_EXPERT, "expert", expert, "outcome", outcome)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 记录 reduce 是否触发（triggered=true 表示走了归纳器二次合成）。 */
    void recordReduce(boolean triggered) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(M_REDUCE, "triggered", String.valueOf(triggered)).increment();
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
