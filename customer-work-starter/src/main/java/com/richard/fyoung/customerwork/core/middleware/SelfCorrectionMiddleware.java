package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.agent.ConversationTurn;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionService;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.NoulVerdict;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.safety.correction.StreamKeywordMatcher;
import com.richard.fyoung.customerwork.safety.correction.UnverifiedClaimTrace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

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
 * <h3>Jev 语义补拦</h3>
 * <p>关键词只认字面，「款项已原路返回您的账户」这类没命中关键词的同义表述会漏过去。开启 Jev 后，
 * 在关键词没命中、且本轮没查过真实状态时，用语义判定再问一次。<b>只收紧不放宽</b>：关键词已经拦下的
 * 不再问 Jev，查过真实状态的也不问，Jev 只能额外拦截；Jev 不可用时退回纯关键词判定。</p>
 *
 * <p>判定放在<b>最终结果</b>上而不是每个文本块结束时：一轮 ReAct 常有多个文本块（「我先查一下」再调工具），
 * 逐块判定会在工具调用前插入阻塞等待，还要付好几次调用。判定的文本是本轮用户实际看到的全部正文。</p>
 *
 * <h3>Jev 拦下时澄清补在哪（取舍）</h3>
 * <p>框架发出最终结果时，答复所在的文本块<b>已经结束</b>（2.0.3：块结束 → 模型调用结束 → 最终结果，
 * {@code SelfCorrectionRealAgentStreamTest} 的框架事实探针钉住了这一点）。三种放法：</p>
 * <table border="1">
 *   <tr><th>放法</th><th>AG-UI 协议</th><th>AG-UI 落库</th><th>外层按块缓冲的过滤</th></tr>
 *   <tr><td>块结束之后往同一块补增量</td><td>✘ 已结束的消息又来内容</td><td>✔</td>
 *       <td>✘ 敏感词过滤压住的尾巴再也等不到放行</td></tr>
 *   <tr><td>另起一个完整的块</td><td>✔</td><td>✘ 只取最后一条消息，历史里只剩澄清、丢了答复正文</td><td>✔</td></tr>
 *   <tr><td><b>扣住答复块的结束等判定，拦下则补在块结束之前</b></td><td>✔</td><td>✔</td><td>✔</td></tr>
 * </table>
 * <p>采用第三种：Jev 还可能出手时（开启了 Jev、本轮没查过真实状态、关键词没拦过），答复块的结束事件连同紧随其后的
 * 模型调用结束一并扣住，等最终结果判定——拦下则澄清补进同一块、赶在块结束之前，放行则原样放出。
 * 对一条资金合规防线来说，留痕里看不出用户当时读到了什么是不可接受的，这是舍弃第二种的原因。</p>
 * <p><b>代价</b>：块结束晚到一次 Jev 判定的耗时（上限是 Jev 的超时）。正文增量照常逐片下发，受影响的是
 * 等块结束才放行的东西——AG-UI 的消息结束、外层敏感词过滤压着的尾巴几个字，以及开启脱敏时整块正文（脱敏本就整块缓冲）。
 * 只延后、不改序、不丢弃：还有下文（工具调用、新一轮推理）就立即放出，流结束或出错时也照样放出。
 * Jev 关闭（默认）时事件时序完全不变。</p>
 * <p><b>兜底</b>：最后一次模型调用在正文之后还发起了工具调用（如等待审批）时，答复块早在工具调用开始时就放出去了，
 * 判定出来时已无块可补——澄清退到悬空块或单独成块（{@link AnswerAppendix#closingBlock}），协议合法、流式用户看得到，
 * AG-UI 落库则只剩澄清。</p>
 *
 * <h3>改写最终结果只换内容</h3>
 * <p>最终结果的消息身份、元数据与其余内容块原样保留（{@link AnswerAppendix#appendTo}）：框架标的结束原因要原样交给外层
 * 终止采集（轮次用尽时 H5 据此显示「答复尚未完成」），挂起的工具调用与消息 id 是 AG-UI 生成审批中断的依据。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class SelfCorrectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SelfCorrectionMiddleware.class);

    private static final String CODE_CHECK_FAIL = "SELF_CORRECTION_ERROR";
    private static final String M_HIT = "customerwork.selfcorrection.unverified.claim";
    private static final String AUDIT_TYPE = "self-correction-unverified-claim";
    private static final String JEV_TITLE = "答复安全闸门";
    private static final String JEV_HIT_LABEL = "Jev 语义判定";
    private static final String JEV_STAGE = "jev";
    /** 澄清单独成块时所用的块标识。 */
    private static final String CLARIFICATION_BLOCK_ID = "self-correction-clarification";

    private final boolean enabled;
    private final List<String> paymentKeywords;
    private final Set<String> evidenceTools;
    private final AnswerAppendix clarification;
    private final boolean handoffOnHit;
    private final ObjectProvider<HandoffService> handoffProvider;
    private final AuditSink auditSink;
    private final MeterRegistry meterRegistry;
    /** 取不到时（未开启 Jev）退回纯关键词判定。 */
    private final Supplier<JevDecisionService> jevSupplier;
    private final JevRunMode mode;

    /** Spring 装配给 C 端：一律 LIVE，不发决策事件。 */
    @Autowired
    public SelfCorrectionMiddleware(CustomerWorkProperties properties,
                                    ObjectProvider<HandoffService> handoffProvider,
                                    ObjectProvider<AuditSink> auditSinkProvider,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider,
                                    ObjectProvider<JevDecisionService> jevProvider) {
        this(properties, handoffProvider, auditSinkProvider, meterRegistryProvider, jevProvider::getIfAvailable,
            JevRunMode.LIVE);
    }

    /** 不接 Jev 的构造：纯关键词判定。 */
    public SelfCorrectionMiddleware(CustomerWorkProperties properties,
                                    ObjectProvider<HandoffService> handoffProvider,
                                    ObjectProvider<AuditSink> auditSinkProvider,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(properties, handoffProvider, auditSinkProvider, meterRegistryProvider, () -> null, JevRunMode.LIVE);
    }

    /**
     * 显式指定 Jev 与运行模式（后台用 {@link JevRunMode#LIVE_TRACED}：真拦截并展示判定）。
     *
     * @param jevSupplier 每次判定现取，返回 null 表示未开启 Jev
     */
    public SelfCorrectionMiddleware(CustomerWorkProperties properties,
                                    ObjectProvider<HandoffService> handoffProvider,
                                    ObjectProvider<AuditSink> auditSinkProvider,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider,
                                    Supplier<JevDecisionService> jevSupplier,
                                    JevRunMode mode) {
        this.jevSupplier = jevSupplier;
        this.mode = mode;
        HooksProperties.SelfCorrection cfg = properties.getHooks().getSelfCorrection();
        this.enabled = cfg.isEnabled();
        this.paymentKeywords = List.copyOf(cfg.getPaymentKeywords());
        this.evidenceTools = Set.copyOf(cfg.getEvidenceTools());
        this.clarification = new AnswerAppendix(cfg.getClarification(), CLARIFICATION_BLOCK_ID);
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
        // concatMap 而非 map：一个事件可能产出 0 个（尾部还不能放行、块结束被扣住）或多个（正文 + 澄清），且必须保序。
        // 扣住的事件只延后、不丢弃：流正常结束或出错时照样放出
        return next.apply(input)
            .concatMap(event -> guard(agent, ctx, event, trace, state))
            .concatWith(Flux.defer(() -> Flux.fromIterable(state.releaseHeld())))
            .onErrorResume(e -> Flux.fromIterable(state.releaseHeld()).concatWith(Flux.error(e)));
    }

    /** 扣着答复块结束时先看这个事件说明了什么，再交给常规判定。 */
    private Flux<AgentEvent> guard(Agent agent, RuntimeContext ctx, AgentEvent event,
                                   UnverifiedClaimTrace trace, OutboundState state) {
        if (state.holding() && !isMainResult(event)) {
            if (event instanceof ModelCallEndEvent) {
                // 答复块之后紧跟的模型调用结束（框架事实）：一并扣住，放出时保持原序
                state.holdTrailing(event);
                return Flux.empty();
            }
            // 还有下文（工具调用、新一轮推理……）：刚才那一块不是最终答复，扣住的事件原样放出
            return Flux.fromIterable(state.releaseHeld()).concatWith(inspect(agent, ctx, event, trace, state));
        }
        return inspect(agent, ctx, event, trace, state);
    }

    private Flux<AgentEvent> inspect(Agent agent, RuntimeContext ctx, AgentEvent event,
                                     UnverifiedClaimTrace trace, OutboundState state) {
        try {
            if (event instanceof ToolCallStartEvent call) {
                // 工具调用事件与正文增量走同一条事件流，且必然先于最终答复到达——
                // 因此不需要在 onActing 与 onAgent 之间共享状态，也就不依赖
                // 「框架对 onActing 的调用是否在本方法的 next 链上」这个假设
                trace.recordTool(call.getToolCallName());
                return Flux.just(event);
            }
            if (event instanceof TextBlockStartEvent start) {
                state.opened(start);
                return Flux.just(event);
            }
            if (event instanceof TextBlockDeltaEvent delta) {
                return guardDelta(agent, ctx, delta, trace, state);
            }
            if (event instanceof TextBlockEndEvent end) {
                return guardBlockEnd(end, trace, state);
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
            return releaseThen(state, event);
        }
    }

    /** 流式正文：逐片过匹配器，命中即停止输出并补澄清。 */
    private Flux<AgentEvent> guardDelta(Agent agent, RuntimeContext ctx, TextBlockDeltaEvent delta,
                                        UnverifiedClaimTrace trace, OutboundState state) {
        state.remember(delta);
        if (state.tripped) {
            // 已经拦下过：后续片段一律丢弃
            return Flux.empty();
        }
        StreamKeywordMatcher matcher = state.matchers.computeIfAbsent(
            String.valueOf(delta.getBlockId()), k -> new StreamKeywordMatcher(paymentKeywords));
        if (hasEvidence(trace)) {
            // 本轮查过真实状态：正文是对系统状态的转述，不过匹配器、原样放行。匹配器命中之后就不再吐字，
            // 若先喂给它再补回关键词，命中点之后的每一片都会被关键词顶替（「已退款已退款……」）
            String text = delta.getDelta() == null ? "" : delta.getDelta();
            return textDelta(delta, matcher.flush() + text);
        }
        String emit = matcher.accept(delta.getDelta());
        if (!matcher.isMatched()) {
            return textDelta(delta, emit);
        }
        state.tripped = true;
        onHit(agent, ctx, trace, matcher.matchedKeyword(), "stream");
        return textDelta(delta, emit)
            .concatWith(Flux.just(clarification.into(delta.getReplyId(), delta.getBlockId())));
    }

    /**
     * 块结束：未命中时把缓冲区里留的尾巴补发，否则正文末尾几个字会被吞掉。
     *
     * <p>这一块若是用户正在读的答复、而 Jev 还可能判它，结束事件先扣住：判定要等最终结果，
     * 那时再补澄清就只能补在块结束之后了（取舍见类注释）。可能出错的判定排在取尾巴之前、扣住排在最后一步——
     * 中途出错走 fail-open 时，尾巴还在匹配器里、块结束也只放一次。</p>
     */
    private Flux<AgentEvent> guardBlockEnd(TextBlockEndEvent end, UnverifiedClaimTrace trace, OutboundState state) {
        state.closed(end);
        boolean hold = awaitsJevVerdict(end, trace, state);
        StreamKeywordMatcher matcher = state.matchers.get(String.valueOf(end.getBlockId()));
        String rest = matcher == null || state.tripped ? "" : matcher.flush();
        Flux<AgentEvent> tail = textDelta(end.getReplyId(), end.getBlockId(), rest);
        if (!hold) {
            return tail.concatWith(Flux.just(end));
        }
        state.hold(end);
        return tail;
    }

    /** 这一块是否可能就是 Jev 要判的最终答复：主 Agent 的、用户刚读到的正文，本轮还没拦过也没查过真实状态。 */
    private boolean awaitsJevVerdict(TextBlockEndEvent end, UnverifiedClaimTrace trace, OutboundState state) {
        return end.getSource() == null
            && !state.tripped
            && !hasEvidence(trace)
            && state.lastTextIn(end.getReplyId())
            && activeJev(state) != null;
    }

    /**
     * 最终结果：非流式路径（{@code chat()}、工作台、渠道）整段判定并改写；流式路径在这里补上 Jev 的判定。
     *
     * <p>流式已经拦过时这里不再重复处置，只把最终结果补上澄清——同一轮不该记两次命中。</p>
     */
    private Flux<AgentEvent> guardFinalResult(Agent agent, RuntimeContext ctx, AgentResultEvent result,
                                              UnverifiedClaimTrace trace, OutboundState state) {
        Msg msg = result.getResult();
        String text = msg == null ? null : msg.getTextContent();
        if (text == null || text.isBlank()) {
            return releaseThen(state, result);
        }
        if (state.tripped) {
            return releaseThen(state, new AgentResultEvent(clarification.appendTo(msg)));
        }
        if (hasEvidence(trace)) {
            return releaseThen(state, result);
        }
        String hit = firstHit(text);
        if (hit == null) {
            return jevGuard(agent, ctx, result, msg, text, trace, state);
        }
        state.tripped = true;
        onHit(agent, ctx, trace, hit, "final");
        return Flux.fromIterable(blockedEvents(msg, List.of(), state));
    }

    /**
     * 关键词没命中、本轮也没查过真实状态时，用 Jev 语义判定补拦。
     *
     * <p>只判主 Agent 的最终结果（子 Agent 的结果不作为答复展示），每轮只判一次。
     * 失败一律放行原结果（fail-open），与本类对自身故障的处理一致。</p>
     */
    private Flux<AgentEvent> jevGuard(Agent agent, RuntimeContext ctx, AgentResultEvent result, Msg msg, String text,
                                      UnverifiedClaimTrace trace, OutboundState state) {
        JevDecisionService jev = activeJev(state);
        if (jev == null || result.getSource() != null) {
            return releaseThen(state, result);
        }
        state.jevChecked = true;
        String judged = state.seenText.length() > 0 ? state.seenText.toString() : text;
        return jev.judgePaymentClaim(judged)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .map(verdict -> applyJev(jev, agent, ctx, result, msg, trace, state, verdict))
            .onErrorResume(e -> {
                log.error("[FIX] jev payment claim check failed, passing through, code={}", CODE_CHECK_FAIL, e);
                return Mono.fromSupplier(() -> passedEvents(List.of(), state, result));
            })
            .flatMapMany(Flux::fromIterable);
    }

    private List<AgentEvent> applyJev(JevDecisionService jev, Agent agent, RuntimeContext ctx,
                                      AgentResultEvent result, Msg msg, UnverifiedClaimTrace trace,
                                      OutboundState state, Optional<NoulVerdict> maybe) {
        if (maybe.isEmpty()) {
            jev.recordDecision(JevDecisionEvent.POINT_PAYMENT_CLAIM, JevDecisionEvent.RESULT_DEGRADED, mode);
            List<AgentEvent> decisions = mode.emit()
                ? List.of(JevDecisionEvent.degraded(JevDecisionEvent.POINT_PAYMENT_CLAIM, JEV_TITLE))
                : List.of();
            return passedEvents(decisions, state, result);
        }
        NoulVerdict verdict = maybe.get();
        boolean blocked = verdict.atLeast(jev.properties().getPaymentClaim().getMinProbability());
        boolean execute = blocked && mode.act();
        jev.recordDecision(JevDecisionEvent.POINT_PAYMENT_CLAIM,
            blocked ? JevDecisionEvent.RESULT_BLOCKED : JevDecisionEvent.RESULT_PASSED, mode);
        if (execute) {
            state.tripped = true;
            onHit(agent, ctx, trace, JEV_HIT_LABEL, JEV_STAGE);
        }
        List<AgentEvent> decisions = mode.emit()
            ? List.of(JevDecisionEvent.decided(mode, JevDecisionEvent.POINT_PAYMENT_CLAIM, JEV_TITLE,
                String.format("回复断言资金已到账的概率 %.2f", verdict.probability()),
                blocked ? blockAction() : "放行", execute, verdict.probability(), verdict.model(),
                verdict.latencyMs()))
            : List.of();
        return execute ? blockedEvents(msg, decisions, state) : passedEvents(decisions, state, result);
    }

    /** 放行：决策事件（后台展示用）、扣住的事件、原最终结果，依次原样放出。 */
    private List<AgentEvent> passedEvents(List<AgentEvent> decisions, OutboundState state, AgentResultEvent result) {
        List<AgentEvent> events = new ArrayList<>(decisions);
        events.addAll(state.releaseHeld());
        events.add(result);
        return events;
    }

    /**
     * 拦下：澄清补到用户正在读的那段正文末尾，再给出改写后的最终结果。
     *
     * <p>流式那一处按优先级落位：扣住的答复块（补在它的结束之前）→ 悬空块或单独成块。整轮没流式过正文则不补——
     * 流式消费方会拿最终结果补全文，澄清已经在里面了；此时再补一段增量，反倒会让它们只显示这段澄清。</p>
     */
    private List<AgentEvent> blockedEvents(Msg msg, List<AgentEvent> decisions, OutboundState state) {
        // 先把可能出错的改写做完再动扣住的事件：中途出错走 fail-open 时，它们还在原处等着放出
        AgentResultEvent rewritten = new AgentResultEvent(clarification.appendTo(msg));
        List<AgentEvent> events = new ArrayList<>(decisions);
        TextBlockEndEvent held = state.heldEnd();
        if (held != null) {
            events.add(clarification.into(held.getReplyId(), held.getBlockId()));
            events.addAll(state.releaseHeld());
        } else if (state.textStreamed()) {
            events.addAll(clarification.closingBlock(state.takeOpenBlock()));
        }
        events.add(rewritten);
        return events;
    }

    /** 本轮还能做 Jev 判定时返回服务；未开启、资金断言判定关闭或本轮已判过时返回 null。 */
    private JevDecisionService activeJev(OutboundState state) {
        if (state.jevChecked) {
            return null;
        }
        JevDecisionService jev = jevSupplier.get();
        return jev != null && jev.properties().getPaymentClaim().isEnabled() ? jev : null;
    }

    private String blockAction() {
        return handoffOnHit && handoffProvider.getIfAvailable() != null ? "追加否定澄清并转人工" : "追加否定澄清";
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
        handoff(agent, ctx, keyword);
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
     *
     * <p>替本轮干活的内部调用（多专家的专家 / 归纳器等）不自己转：它跑在派生会话上，在那里建单没人能接到用户。
     * 交给本轮的组织者，由它落到用户会话上、只转一次（见 {@link ConversationTurn}）。</p>
     */
    private void handoff(Agent agent, RuntimeContext ctx, String keyword) {
        if (!handoffOnHit) {
            return;
        }
        String reason = "智能体给出未经核实的资金结论：" + keyword;
        ConversationTurn owner = ConversationTurn.delegatedBy(ctx);
        if (owner != null) {
            owner.escalate(new ConversationTurn.Escalation(agentName(agent), null, reason));
            return;
        }
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        HandoffService handoffService = handoffProvider.getIfAvailable();
        if (handoffService == null) {
            return;
        }
        try {
            handoffService.create(sessionId, reason);
        } catch (Exception e) {
            log.error("[FIX] handoff on unverified claim failed, code={}, session={}",
                CODE_CHECK_FAIL, sessionId, e);
        }
    }

    private static boolean isMainResult(AgentEvent event) {
        return event instanceof AgentResultEvent && event.getSource() == null;
    }

    /** 扣住的事件先放出，再放本事件。 */
    private static Flux<AgentEvent> releaseThen(OutboundState state, AgentEvent event) {
        return Flux.fromIterable(state.releaseHeld()).concatWith(Flux.just(event));
    }

    private static Flux<AgentEvent> textDelta(TextBlockDeltaEvent origin, String text) {
        return textDelta(origin.getReplyId(), origin.getBlockId(), text);
    }

    private static Flux<AgentEvent> textDelta(String replyId, String blockId, String text) {
        return text.isEmpty() ? Flux.empty() : Flux.just(new TextBlockDeltaEvent(replyId, blockId, text));
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
        /** 本轮用户实际看到的全部正文，供 Jev 语义判定。 */
        private final StringBuilder seenText = new StringBuilder();
        /** 最近一段非空正文所在的回复：据此判断刚结束的块是不是用户正在读的那一块。 */
        private volatile String lastTextReplyId;
        /** 每轮只做一次 Jev 判定。 */
        private volatile boolean jevChecked;
        /** 已开始、尚未结束的主 Agent 文本块；模型中途失败时它会一直悬空。 */
        private TextBlockStartEvent openBlock;
        /** 等 Jev 判定而扣住的答复块结束，以及其后紧随的模型调用结束。 */
        private TextBlockEndEvent heldEnd;
        private final List<AgentEvent> heldTrailing = new ArrayList<>();

        private synchronized void remember(TextBlockDeltaEvent delta) {
            if (delta.getDelta() == null || delta.getDelta().isEmpty()) {
                return;
            }
            seenText.append(delta.getDelta());
            lastTextReplyId = delta.getReplyId();
        }

        private boolean textStreamed() {
            return lastTextReplyId != null;
        }

        private boolean lastTextIn(String replyId) {
            return lastTextReplyId != null && lastTextReplyId.equals(replyId);
        }

        private synchronized void opened(TextBlockStartEvent start) {
            if (start.getSource() == null) {
                openBlock = start;
            }
        }

        private synchronized void closed(TextBlockEndEvent end) {
            if (end.getSource() == null && openBlock != null
                && Objects.equals(openBlock.getReplyId(), end.getReplyId())) {
                openBlock = null;
            }
        }

        private synchronized TextBlockStartEvent takeOpenBlock() {
            TextBlockStartEvent dangling = openBlock;
            openBlock = null;
            return dangling;
        }

        private synchronized boolean holding() {
            return heldEnd != null;
        }

        private synchronized TextBlockEndEvent heldEnd() {
            return heldEnd;
        }

        private synchronized void hold(TextBlockEndEvent end) {
            heldEnd = end;
        }

        private synchronized void holdTrailing(AgentEvent event) {
            heldTrailing.add(event);
        }

        /** 放出扣住的事件（原序）；之后不再扣着任何东西。 */
        private synchronized List<AgentEvent> releaseHeld() {
            if (heldEnd == null) {
                return List.of();
            }
            List<AgentEvent> released = new ArrayList<>(heldTrailing.size() + 1);
            released.add(heldEnd);
            released.addAll(heldTrailing);
            heldEnd = null;
            heldTrailing.clear();
            return released;
        }
    }

    /** 顺序契约见 {@link MiddlewareOrders}：模型输出的自我纠错。 */
    @Override
    public int order() {
        return MiddlewareOrders.SELF_CORRECTION;
    }
}
