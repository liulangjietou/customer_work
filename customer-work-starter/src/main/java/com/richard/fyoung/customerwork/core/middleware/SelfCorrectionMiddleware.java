package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.safety.correction.StreamKeywordMatcher;
import com.richard.fyoung.customerwork.safety.correction.UnverifiedClaimTrace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 拦住智能体凭空告诉用户「钱已经退了」。
 *
 * <h3>它防的是什么</h3>
 * <p>客服智能体<b>没有能力完成打款</b>——{@code submitRefund} 的工具描述写得很清楚：
 * 只生成待人工确认的退款工单。所以「已退款 / 已到账 / 款项已退」这类<b>完成时态断言</b>
 * 由它说出口，只有两种可能：凭空编造，或者把"已提交工单"错当成了"钱已到账"。
 * 两种都会直接变成客诉与合规问题。</p>
 *
 * <h3>改了什么</h3>
 * <p>此前这个中间件是<b>检测 + 打一行 {@code log.info}</b>，内容照原样发给用户，且默认关闭。
 * 三处都不成立：</p>
 * <ol>
 *   <li><b>只看 {@link AgentResultEvent} 在流式路径上是假性生效</b>。接入层把
 *       {@link TextBlockDeltaEvent} 逐片推给前端，正文流完之后最终结果会被丢弃——
 *       等它到达时用户早就读完了。同一个坑 {@code SensitiveWordMiddleware} 已经踩过并写进了注释。</li>
 *   <li><b>判定不看工具上下文</b>。类注释声称判定「在未走退款工具的情况下」，
 *       而代码里只有纯关键词 {@code contains}，没有任何工具判断。</li>
 *   <li><b>默认关闭</b>。配合"只打日志"，等于这条防线完全不存在。</li>
 * </ol>
 *
 * <h3>判定依据从哪来</h3>
 * <p>工具调用以 {@link ToolCallStartEvent} 的形式流过<b>同一条事件流</b>，且必然先于最终答复到达。
 * 因此本类不在 {@code onActing} 与 {@code onAgent} 之间共享状态——那要靠 Reactor Context 传播，
 * 而「框架对 {@code onActing} 的调用是否落在本方法的 next 链上」是个不该依赖的假设
 * （引用回传那次就栽在同类假设上：框架用 {@code Mono.block()} 调检索，Context 直接断了）。</p>
 *
 * <h3>判定：转述还是编造</h3>
 * <table border="1">
 *   <tr><th>本轮工具</th><th>回复「已退款」</th><th>处置</th></tr>
 *   <tr><td>调过 {@code queryRefundProgress} 等查询工具</td><td>转述系统真实状态</td><td>放行</td></tr>
 *   <tr><td>一个都没调</td><td>凭空生成</td><td>拦</td></tr>
 *   <tr><td>只调了 {@code submitRefund}</td><td>工单≠已打款</td><td><b>拦</b></td></tr>
 * </table>
 * <p>第三行是最隐蔽的一种：链路上看一切正常，工具调了也成功了，错的是模型对工具语义的理解。</p>
 *
 * <h3>流式下的固有限制</h3>
 * <p>命中时前面的字已经在用户屏幕上，收不回来（与出站敏感词过滤同一限制）。处置是
 * <b>立即停止后续输出 + 追加澄清 + 转人工</b>——澄清话术必须明确否定刚才那段，
 * 只说"正在处理"等于默认了前面的说法。要一个字都不漏只能整段缓冲后再发，那就没有流式了。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class SelfCorrectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SelfCorrectionMiddleware.class);

    private static final String CODE_CHECK_FAIL = "SELF_CORRECTION_ERROR";
    private static final String M_HIT = "customerwork.selfcorrection.unverified.claim";
    private static final String AUDIT_TYPE = "self-correction-unverified-claim";

    private final boolean enabled;
    private final List<String> paymentKeywords;
    private final Set<String> evidenceTools;
    private final String clarification;
    private final boolean handoffOnHit;
    private final ObjectProvider<HandoffService> handoffProvider;
    private final AuditSink auditSink;
    private final MeterRegistry meterRegistry;

    public SelfCorrectionMiddleware(CustomerWorkProperties properties,
                                    ObjectProvider<HandoffService> handoffProvider,
                                    ObjectProvider<AuditSink> auditSinkProvider,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        HooksProperties.SelfCorrection cfg = properties.getHooks().getSelfCorrection();
        this.enabled = cfg.isEnabled();
        this.paymentKeywords = List.copyOf(cfg.getPaymentKeywords());
        this.evidenceTools = Set.copyOf(cfg.getEvidenceTools());
        this.clarification = cfg.getClarification();
        this.handoffOnHit = cfg.isHandoffOnHit();
        this.handoffProvider = handoffProvider;
        this.auditSink = auditSinkProvider.getIfAvailable();
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled || paymentKeywords.isEmpty()) {
            return next.apply(input);
        }
        // 每次调用一份独立状态：中间件是单例，放字段会让并发会话互相串
        UnverifiedClaimTrace trace = new UnverifiedClaimTrace();
        OutboundState state = new OutboundState();
        // concatMap 而非 map：一个增量可能产出 0 个（尾部还不能放行）或 2 个（正文 + 澄清），且必须保序
        return next.apply(input).concatMap(event -> guard(agent, ctx, event, trace, state));
    }

    private Flux<AgentEvent> guard(Agent agent, RuntimeContext ctx, AgentEvent event,
                                   UnverifiedClaimTrace trace, OutboundState state) {
        try {
            if (event instanceof ToolCallStartEvent call) {
                // 工具调用事件与正文增量走同一条事件流，且必然先于最终答复到达——
                // 因此不需要在 onActing 与 onAgent 之间共享状态，也就不依赖
                // 「框架对 onActing 的调用是否在本方法的 next 链上」这个假设
                trace.recordTool(call.getToolCallName());
                return Flux.just(event);
            }
            if (event instanceof TextBlockDeltaEvent delta) {
                return guardDelta(agent, ctx, delta, trace, state);
            }
            if (event instanceof TextBlockEndEvent end) {
                return guardBlockEnd(end, state);
            }
            if (event instanceof AgentResultEvent result) {
                return guardFinalResult(agent, ctx, result, trace, state);
            }
            return Flux.just(event);
        } catch (Exception e) {
            // fail-open：本中间件是补充防线，它自己出故障不该让客服对话中断。
            // 与出站敏感词过滤的 fail-closed 刻意相反——那边拦的是违规内容，这边拦的是措辞不准
            log.error("[FIX] self-correction check failed, passing through, code={}, agent={}",
                CODE_CHECK_FAIL, agentName(agent), e);
            return Flux.just(event);
        }
    }

    /** 流式正文：逐片过匹配器，命中即停止输出并补澄清。 */
    private Flux<AgentEvent> guardDelta(Agent agent, RuntimeContext ctx, TextBlockDeltaEvent delta,
                                        UnverifiedClaimTrace trace, OutboundState state) {
        StreamKeywordMatcher matcher = state.matchers.computeIfAbsent(
            String.valueOf(delta.getBlockId()), k -> new StreamKeywordMatcher(paymentKeywords));
        if (state.tripped) {
            // 已经拦下过：后续片段一律丢弃
            return Flux.empty();
        }
        String emit = matcher.accept(delta.getDelta());
        if (!matcher.isMatched()) {
            return emit.isEmpty()
                ? Flux.empty()
                : Flux.just(new TextBlockDeltaEvent(delta.getReplyId(), delta.getBlockId(), emit));
        }
        // 命中了关键词，但本轮查过真实状态就是正常转述，原样放行
        if (hasEvidence(trace)) {
            String rest = emit + matcher.matchedKeyword() + matcher.flush();
            return rest.isEmpty()
                ? Flux.empty()
                : Flux.just(new TextBlockDeltaEvent(delta.getReplyId(), delta.getBlockId(), rest));
        }
        state.tripped = true;
        onHit(agent, ctx, trace, matcher.matchedKeyword(), "stream");
        Flux<AgentEvent> head = emit.isEmpty()
            ? Flux.empty()
            : Flux.just(new TextBlockDeltaEvent(delta.getReplyId(), delta.getBlockId(), emit));
        return head.concatWith(Flux.just(
            new TextBlockDeltaEvent(delta.getReplyId(), delta.getBlockId(), clarification)));
    }

    /** 块结束：未命中时把缓冲区里留的尾巴补发，否则正文末尾几个字会被吞掉。 */
    private Flux<AgentEvent> guardBlockEnd(TextBlockEndEvent end, OutboundState state) {
        StreamKeywordMatcher matcher = state.matchers.get(String.valueOf(end.getBlockId()));
        if (matcher == null || state.tripped) {
            return Flux.just(end);
        }
        String rest = matcher.flush();
        return rest.isEmpty()
            ? Flux.just(end)
            : Flux.just(new TextBlockDeltaEvent(end.getReplyId(), end.getBlockId(), rest), end);
    }

    /**
     * 非流式路径（{@code chat()}、工作台、渠道）：最终结果整段判定并改写。
     *
     * <p>流式已经拦过时这里不再重复处置，只把正文补上澄清——同一轮不该记两次命中。</p>
     */
    private Flux<AgentEvent> guardFinalResult(Agent agent, RuntimeContext ctx, AgentResultEvent result,
                                              UnverifiedClaimTrace trace, OutboundState state) {
        Msg msg = result.getResult();
        String text = msg == null ? null : msg.getTextContent();
        if (text == null || text.isBlank()) {
            return Flux.just(result);
        }
        if (state.tripped) {
            return Flux.just(new AgentResultEvent(rebuild(msg, text + clarification)));
        }
        String hit = firstHit(text);
        if (hit == null || hasEvidence(trace)) {
            return Flux.just(result);
        }
        state.tripped = true;
        onHit(agent, ctx, trace, hit, "final");
        return Flux.just(new AgentResultEvent(rebuild(msg, text + clarification)));
    }

    private String firstHit(String text) {
        for (String keyword : paymentKeywords) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    /** 本轮是否查过可作为依据的真实状态。 */
    private boolean hasEvidence(UnverifiedClaimTrace trace) {
        return trace != null && trace.calledAnyOf(evidenceTools);
    }

    /** 命中处置：指标 + 日志 + 审计 + 转人工。四件事互不依赖，任一失败不影响其余。 */
    private void onHit(Agent agent, RuntimeContext ctx, UnverifiedClaimTrace trace,
                       String keyword, String stage) {
        if (meterRegistry != null) {
            Counter.builder(M_HIT).tag("stage", stage).register(meterRegistry).increment();
        }
        String sessionId = ctx == null ? null : ctx.getSessionId();
        log.error("[FIX] unverified payment claim blocked, code={}, agent={}, session={}, "
                + "keyword={}, stage={}, calledTools={}",
            CODE_CHECK_FAIL, agentName(agent), sessionId, keyword, stage,
            trace == null ? "?" : trace.calledTools());
        audit(agent, sessionId, keyword, stage, trace);
        handoff(sessionId, keyword);
    }

    private void audit(Agent agent, String sessionId, String keyword, String stage,
                       UnverifiedClaimTrace trace) {
        if (auditSink == null) {
            return;
        }
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("agent", agentName(agent));
            fields.put("session", sessionId);
            fields.put("keyword", keyword);
            fields.put("stage", stage);
            fields.put("calledTools", trace == null ? List.of() : trace.calledTools());
            fields.put("ts", System.currentTimeMillis());
            auditSink.record(AUDIT_TYPE, fields);
        } catch (Exception e) {
            log.error("[FIX] audit record failed, code={}", CODE_CHECK_FAIL, e);
        }
    }

    /**
     * 转人工：资金类误告知一旦发生，人工介入比任何自动补救都可靠。
     *
     * <p>转人工失败不影响拦截本身——澄清话术已经发出去了，用户至少知道那句话不作数。</p>
     */
    private void handoff(String sessionId, String keyword) {
        if (!handoffOnHit || sessionId == null || sessionId.isBlank()) {
            return;
        }
        HandoffService handoffService = handoffProvider.getIfAvailable();
        if (handoffService == null) {
            return;
        }
        try {
            handoffService.create(sessionId, "智能体给出未经核实的资金结论：" + keyword);
        } catch (Exception e) {
            log.error("[FIX] handoff on unverified claim failed, code={}, session={}",
                CODE_CHECK_FAIL, sessionId, e);
        }
    }

    private Msg rebuild(Msg origin, String text) {
        return Msg.builder()
            .role(origin == null || origin.getRole() == null ? MsgRole.ASSISTANT : origin.getRole())
            .name(origin == null ? null : origin.getName())
            .content(TextBlock.builder().text(text).build())
            .build();
    }

    private String agentName(Agent agent) {
        return agent == null ? "?" : agent.getName();
    }

    /** 供既有单测复用的纯判定。 */
    boolean promisesPayment(String text) {
        return text != null && !text.isBlank() && firstHit(text) != null;
    }

    /** 一次调用的出站状态。绝不放中间件字段——单例上放可变状态会让并发会话串味。 */
    private static final class OutboundState {
        private final Map<String, StreamKeywordMatcher> matchers = new ConcurrentHashMap<>();
        private volatile boolean tripped;
    }

    /** 顺序契约见 {@link MiddlewareOrders}：模型输出的自我纠错。 */
    @Override
    public int order() {
        return MiddlewareOrders.SELF_CORRECTION;
    }
}
