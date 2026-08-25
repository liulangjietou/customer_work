package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWord;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilterResult;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHit;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitDirection;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitRecord;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordStreamGuard;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import com.richard.fyoung.customerwork.infra.config.properties.SensitiveWordProperties;

/**
 * 敏感词"一次拦截"中间件（2.0 Middleware，「安全合规 · 内容风控」，智能路由中控第一块）。
 *
 * <p>用户输入与 AI 输出各过一遍高性能敏感词自动机（{@link SensitiveWordFilter}），命中即处置——零 LLM、
 * 微秒级、可解释。命中动作分三档：{@code BLOCK}（硬拦截）/ {@code MASK}（打码放行）/ {@code REVIEW}（放行标记）。</p>
 *
 * <ul>
 *   <li><b>入站</b>（{@link #onAgent} 拦 {@link AgentInput#msgs()}，照 {@link PromptInjectionGuardMiddleware}）：
 *       命中 BLOCK 则<b>不调 next</b>、直接返回安全话术（不产生模型调用）；命中 MASK 则打码后放行；
 *       命中 REVIEW 放行但审计标记。</li>
 *   <li><b>出站</b>（改写 {@link AgentResultEvent}，照 {@link MaskingMiddleware}）：AI 回复同样过一遍，
 *       命中 BLOCK 替换为安全兜底话术，命中 MASK 打码。</li>
 *   <li><b>fail-closed</b>：过滤器自身抛异常时，入站按<b>拦截</b>处理、出站替换为<b>安全兜底</b>——安全优先。
 *       这与 {@link MaskingMiddleware} 的 fail-open（原样放行）刻意<b>相反</b>：脱敏漏一次只是隐私风险，
 *       敏感词漏一次是合规事故，故内容风控必须 fail-closed。</li>
 * </ul>
 *
 * <p>默认关闭（{@code customer-work.sensitive-word.enabled=false}）：与 {@link SensitiveWordFilter} 同用
 * {@code @ConditionalOnProperty} 门控，关闭时整套 Bean（含本中间件与其依赖的过滤器）全不装配，启动零开销；
 * 保留的运行时 {@code enabled} 判断仅服务于直接构造（单测）与防御式收口。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(prefix = "customer-work.sensitive-word", name = "enabled", havingValue = "true")
public class SensitiveWordMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordMiddleware.class);

    private static final String CODE_FILTER_FAIL = "SENSITIVE-FILTER-FAIL";
    private static final String M_INBOUND_BLOCKED = "customerwork.sensitive.inbound.blocked";
    private static final String M_INBOUND_MASKED = "customerwork.sensitive.inbound.masked";
    private static final String M_OUTBOUND_BLOCKED = "customerwork.sensitive.outbound.blocked";
    private static final String M_OUTBOUND_MASKED = "customerwork.sensitive.outbound.masked";
    private static final String ASSISTANT = "assistant";

    private final boolean enabled;
    private final boolean inboundEnabled;
    private final boolean outboundEnabled;
    private final String inboundSafeReply;
    private final String outboundSafeReply;

    private final SensitiveWordFilter filter;
    private final AuditSink auditSink;
    private final MeterRegistry meterRegistry;
    /** 命中日志投递出口；命中日志关闭时容器里没有该 Bean，此处为 null，直接跳过记录。 */
    private final SensitiveWordHitSink hitSink;
    /** 命中日志留存的原文片段长度上限。 */
    private final int snippetMaxLength;

    /** 入站命中 BLOCK 的累计次数（供单测断言 / 监控采样）。 */
    private final LongAdder inboundBlockedHits = new LongAdder();

    public SensitiveWordMiddleware(CustomerWorkProperties properties,
                                   SensitiveWordFilter filter,
                                   AuditSink auditSink,
                                   ObjectProvider<MeterRegistry> meterRegistryProvider,
                                   ObjectProvider<SensitiveWordHitSink> hitSinkProvider) {
        SensitiveWordProperties cfg = properties.getSensitiveWord();
        this.enabled = cfg.isEnabled();
        this.inboundEnabled = cfg.inboundEnabled();
        this.outboundEnabled = cfg.outboundEnabled();
        this.inboundSafeReply = cfg.getInboundSafeReply();
        this.outboundSafeReply = cfg.getOutboundSafeReply();
        this.filter = filter;
        this.auditSink = auditSink;
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        this.hitSink = hitSinkProvider == null ? null : hitSinkProvider.getIfAvailable();
        this.snippetMaxLength = cfg.getHitLog().getSnippetMaxLength();
    }

    /** 入站命中 BLOCK 的累计次数（供单测断言）。 */
    long inboundBlockedCount() {
        return inboundBlockedHits.sum();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }

        AgentInput effectiveInput = input;
        if (inboundEnabled) {
            try {
                InboundOutcome outcome = filterInbound(agent, ctx, input);
                if (outcome.blocked) {
                    return Flux.just(new AgentResultEvent(safeMsg(inboundSafeReply)));
                }
                effectiveInput = outcome.input;
            } catch (Exception e) {
                // fail-closed：过滤器故障，入站按拦截处理（安全优先），与 MaskingMiddleware 的 fail-open 相反
                inboundBlockedHits.increment();
                metric(M_INBOUND_BLOCKED);
                log.error("[SENSITIVE] inbound filter failed (fail-closed, blocking), code={}, agent={}",
                    CODE_FILTER_FAIL, agentName(agent), e);
                audit("sensitive-inbound-fail-closed", agent, null);
                return Flux.just(new AgentResultEvent(safeMsg(inboundSafeReply)));
            }
        }

        Flux<AgentEvent> downstream = next.apply(effectiveInput);
        if (outboundEnabled) {
            // 每次调用一份独立的流式状态（缓冲区/拦截标志）：中间件是单例，状态放字段会让并发会话串味。
            // 用 concatMap 而非 map：一个增量事件可能产出 0 个（尾部还不能放行）或 2 个（flush + 原事件），
            // 且必须保序——乱序会让打过码的片段跑到未打码片段后面。
            OutboundStreamState streamState = new OutboundStreamState();
            downstream = downstream.concatMap(event -> filterOutboundEvent(agent, ctx, event, streamState));
        }
        return downstream;
    }

    // ---------------- 入站 ----------------

    private InboundOutcome filterInbound(Agent agent, RuntimeContext ctx, AgentInput input) {
        if (input == null || CollectionUtils.isEmpty(input.msgs())) {
            return InboundOutcome.pass(input);
        }
        List<Msg> msgs = input.msgs();
        List<Msg> rebuilt = new ArrayList<>(msgs.size());
        boolean changed = false;
        for (Msg msg : msgs) {
            MsgScan scan = scanMsg(msg);
            if (scan.decision == SensitiveWordAction.BLOCK) {
                inboundBlockedHits.increment();
                metric(M_INBOUND_BLOCKED);
                log.info("[SENSITIVE] inbound blocked, category={}, word={}, agent={}",
                    categoryOf(scan.topWord), wordOf(scan.topWord), agentName(agent));
                report("sensitive-inbound-block", SensitiveWordHitDirection.INBOUND, agent, ctx, scan);
                return InboundOutcome.blocked();
            }
            if (scan.decision == SensitiveWordAction.MASK && scan.maskedMsg != null) {
                metric(M_INBOUND_MASKED);
                report("sensitive-inbound-mask", SensitiveWordHitDirection.INBOUND, agent, ctx, scan);
                rebuilt.add(scan.maskedMsg);
                changed = true;
            } else {
                if (scan.decision == SensitiveWordAction.REVIEW) {
                    report("sensitive-inbound-review", SensitiveWordHitDirection.INBOUND, agent, ctx, scan);
                }
                rebuilt.add(msg);
            }
        }
        return changed ? InboundOutcome.masked(new AgentInput(rebuilt)) : InboundOutcome.pass(input);
    }

    // ---------------- 出站 ----------------

    /**
     * 出站事件分流：流式正文增量走滑动缓冲，块结束时 flush，其余事件（含最终结果）走整段过滤。
     *
     * <p><b>为什么必须拦增量事件</b>：接入层（admin ChatService / 8080 SSE）把
     * {@link TextBlockDeltaEvent} 逐片推给前端，正文流完之后最终的 {@link AgentResultEvent}
     * 会被直接丢弃。只改最终结果等于改了个没人看的事件——过滤"记录了命中、也算出了打码文本"，
     * 但用户屏幕上是未过滤的原文。这种假性生效比不做更危险，所以增量必须过。</p>
     */
    private Flux<AgentEvent> filterOutboundEvent(Agent agent, RuntimeContext ctx, AgentEvent event,
                                                 OutboundStreamState state) {
        try {
            if (event instanceof TextBlockDeltaEvent delta) {
                return filterDelta(agent, ctx, delta, state);
            }
            if (event instanceof TextBlockEndEvent end) {
                return flushBlock(agent, ctx, end, state);
            }
            return Flux.just(filterOutbound(agent, ctx, event));
        } catch (Exception e) {
            // fail-closed：流式过滤自身故障时不放行未过滤内容，丢弃该片段并记录
            log.error("[SENSITIVE] outbound stream filter failed (fail-closed, dropping chunk), code={}, agent={}",
                CODE_FILTER_FAIL, agentName(agent), e);
            return Flux.empty();
        }
    }

    /**
     * 流式正文增量的滑动缓冲过滤。
     *
     * <p>每片到达后与缓冲区拼接整体过滤，只放行"确定不可能再是某个词前缀"的前半段，
     * 尾部只保留仍可扩展成敏感词的真实歧义前缀等下一片——
     * 否则 {@code 阿根廷} 被拆成三片推送时永远匹配不上。</p>
     *
     * <p><b>BLOCK 在流式下的固有限制</b>：命中时前面的片段已经在用户屏幕上，收不回来。
     * 这里的处置是"立即停止后续输出 + 补一条安全话术"，把伤害面收敛到命中点之后。
     * 要做到一个字都不漏，只能整段缓冲后再发（那就没有流式了）——需要那种强度的场景，
     * 应当在接入层关闭流式，而不是让中间件替所有人做这个取舍。</p>
     */
    private Flux<AgentEvent> filterDelta(Agent agent, RuntimeContext ctx, TextBlockDeltaEvent delta,
                                         OutboundStreamState state) {
        SensitiveWordStreamGuard guard = state.guards.computeIfAbsent(delta.getBlockId(),
            k -> newGuard(agent, ctx));
        if (guard.isBlocked()) {
            // 已经拦下过：后续片段一律丢弃，不再往下发
            return Flux.empty();
        }
        String emit = guard.accept(delta.getDelta());
        if (guard.isBlocked()) {
            metric(M_OUTBOUND_BLOCKED);
            log.info("[SENSITIVE] outbound stream blocked, agent={}", agentName(agent));
        }
        return emit.isEmpty()
            ? Flux.empty()
            : Flux.just(new TextBlockDeltaEvent(delta.getReplyId(), delta.getBlockId(), emit));
    }

    /** 块结束：把缓冲区里留的尾巴过滤后补发，再放行结束事件——不 flush 就会吞掉正文末尾几个字。 */
    private Flux<AgentEvent> flushBlock(Agent agent, RuntimeContext ctx, TextBlockEndEvent end,
                                        OutboundStreamState state) {
        SensitiveWordStreamGuard guard = state.guards.remove(end.getBlockId());
        if (guard == null) {
            return Flux.just(end);
        }
        String tail = guard.flush();
        if (guard.isMasked()) {
            metric(M_OUTBOUND_MASKED);
        }
        return tail.isEmpty()
            ? Flux.just(end)
            : Flux.just(new TextBlockDeltaEvent(end.getReplyId(), end.getBlockId(), tail), end);
    }

    /**
     * 为一个文本块建 guard，命中回调接到既有的审计 + 命中日志出口上。
     *
     * <p>滑动缓冲的实现收敛在 {@link SensitiveWordStreamGuard} 一处——接入层（admin ChatService /
     * 8080 CustomerServiceService）用的是同一个类。两份实现迟早漂移，而这段逻辑一旦漂移就是"某条链路
     * 悄悄漏词"，属于最难发现的那类故障。</p>
     */
    private SensitiveWordStreamGuard newGuard(Agent agent, RuntimeContext ctx) {
        return new SensitiveWordStreamGuard(filter, outboundSafeReply,
            result -> reportStreamHit(result.decision() == SensitiveWordAction.BLOCK
                ? "sensitive-outbound-block" : "sensitive-outbound-mask", agent, ctx, result));
    }

    /** 流式命中的上报（审计 + 命中日志），与整段路径共用同一个出口语义。 */
    private void reportStreamHit(String type, Agent agent, RuntimeContext ctx, SensitiveWordFilterResult result) {
        audit(type, agent, strongestWord(result));
        if (hitSink == null) {
            return;
        }
        try {
            hitSink.emit(SensitiveWordHitRecord.of(SensitiveWordHitDirection.OUTBOUND, result, agentName(agent),
                ctx == null ? null : ctx.getSessionId(), ctx == null ? null : ctx.getUserId(), snippetMaxLength));
        } catch (Exception e) {
            log.error("[SENSITIVE] stream hit log emit failed, code={}", CODE_FILTER_FAIL, e);
        }
    }

    private AgentEvent filterOutbound(Agent agent, RuntimeContext ctx, AgentEvent event) {
        if (!(event instanceof AgentResultEvent result)) {
            return event;
        }
        try {
            MsgScan scan = scanMsg(result.getResult());
            if (scan.decision == SensitiveWordAction.BLOCK) {
                metric(M_OUTBOUND_BLOCKED);
                log.info("[SENSITIVE] outbound blocked, category={}, word={}, agent={}",
                    categoryOf(scan.topWord), wordOf(scan.topWord), agentName(agent));
                report("sensitive-outbound-block", SensitiveWordHitDirection.OUTBOUND, agent, ctx, scan);
                return new AgentResultEvent(safeMsg(outboundSafeReply));
            }
            if (scan.decision == SensitiveWordAction.MASK && scan.maskedMsg != null) {
                metric(M_OUTBOUND_MASKED);
                report("sensitive-outbound-mask", SensitiveWordHitDirection.OUTBOUND, agent, ctx, scan);
                return new AgentResultEvent(scan.maskedMsg);
            }
            if (scan.decision == SensitiveWordAction.REVIEW) {
                report("sensitive-outbound-review", SensitiveWordHitDirection.OUTBOUND, agent, ctx, scan);
            }
            return event;
        } catch (Exception e) {
            // fail-closed：出站过滤故障不放行未过滤内容，替换为安全兜底
            log.error("[SENSITIVE] outbound filter failed (fail-closed), code={}, agent={}",
                CODE_FILTER_FAIL, agentName(agent), e);
            return new AgentResultEvent(safeMsg(outboundSafeReply));
        }
    }

    // ---------------- 单条消息扫描 ----------------

    /**
     * 扫描一条消息的全部文本块，聚合整体决策。命中 MASK 时逐块替换为打码文本（保留角色/名称/元数据），
     * 只改文本内容块（照 {@link MaskingMiddleware}）。
     */
    private MsgScan scanMsg(Msg msg) {
        if (msg == null) {
            return MsgScan.pass();
        }
        List<ContentBlock> blocks = msg.getContent();
        if (CollectionUtils.isEmpty(blocks)) {
            return MsgScan.pass();
        }
        SensitiveWordAction topDecision = null;
        SensitiveWord topWord = null;
        SensitiveWordFilterResult topResult = null;
        List<ContentBlock> rebuilt = new ArrayList<>(blocks.size());
        boolean maskedAny = false;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                SensitiveWordFilterResult res = filter.check(tb.getText());
                if (res.hasHit()) {
                    if (topDecision == null || res.decision().severity() > topDecision.severity()) {
                        topDecision = res.decision();
                        topWord = strongestWord(res);
                        topResult = res;
                    }
                    if (res.masked()) {
                        rebuilt.add(TextBlock.builder().text(res.maskedText()).build());
                        maskedAny = true;
                        continue;
                    }
                }
            }
            rebuilt.add(block);
        }
        if (topDecision == null) {
            return MsgScan.pass();
        }
        Msg maskedMsg = maskedAny ? rebuildMsg(msg, rebuilt) : null;
        return new MsgScan(topDecision, maskedMsg, topWord, topResult);
    }

    /** 取与整体决策同档的首个命中词（用于审计/日志展示）。 */
    private SensitiveWord strongestWord(SensitiveWordFilterResult res) {
        for (SensitiveWordHit hit : res.hits()) {
            if (hit.word().getAction() == res.decision()) {
                return hit.word();
            }
        }
        return res.hits().isEmpty() ? null : res.hits().get(0).word();
    }

    private Msg rebuildMsg(Msg original, List<ContentBlock> rebuilt) {
        return Msg.builder()
            .id(original.getId())
            .name(original.getName())
            .role(original.getRole())
            .content(rebuilt)
            .metadata(original.getMetadata())
            .build();
    }

    // ---------------- 辅助 ----------------

    /**
     * 命中的统一上报出口：审计事件 + 命中日志两件事一起做。
     *
     * <p>合在一个方法里是刻意的——每个命中分支都要同时上报这两处，拆开写迟早会有分支只报一半。
     * 审计走 {@link AuditSink}（实时事件流），命中日志走 {@link SensitiveWordHitSink}（异步落库供后台看板），
     * 两者定位不同：前者面向运行时告警，后者面向事后运营分析。</p>
     */
    private void report(String type, SensitiveWordHitDirection direction, Agent agent,
                        RuntimeContext ctx, MsgScan scan) {
        audit(type, agent, scan.topWord);
        if (hitSink == null || scan.topResult == null) {
            return;
        }
        try {
            hitSink.emit(SensitiveWordHitRecord.of(direction, scan.topResult, agentName(agent),
                ctx == null ? null : ctx.getSessionId(), ctx == null ? null : ctx.getUserId(), snippetMaxLength));
        } catch (Exception e) {
            // 命中日志是旁路观测，任何异常都不能影响已经做出的拦截/打码处置
            log.error("[SENSITIVE] hit log emit failed, code={}, direction={}", CODE_FILTER_FAIL, direction, e);
        }
    }

    private void audit(String type, Agent agent, SensitiveWord word) {
        if (auditSink == null) {
            return;
        }
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("agent", agentName(agent));
            fields.put("category", categoryOf(word));
            fields.put("word", wordOf(word));
            fields.put("action", word == null || word.getAction() == null ? "?" : word.getAction().name());
            fields.put("ts", System.currentTimeMillis());
            auditSink.record(type, fields);
        } catch (Exception e) {
            log.error("[SENSITIVE] audit record failed, code={}", CODE_FILTER_FAIL, e);
        }
    }

    private void metric(String name) {
        if (meterRegistry != null) {
            Counter.builder(name).register(meterRegistry).increment();
        }
    }

    private Msg safeMsg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).name(ASSISTANT).textContent(text).build();
    }

    private static String agentName(Agent agent) {
        return agent == null ? "?" : agent.getName();
    }

    private static String categoryOf(SensitiveWord word) {
        return word == null || word.getCategory() == null ? "?" : word.getCategory().name();
    }

    private static String wordOf(SensitiveWord word) {
        return word == null ? "?" : word.getWord();
    }

    /** 入站处置结果：命中 BLOCK（短路）/ 打码后重建的 input / 原样放行。 */
    private static final class InboundOutcome {
        private final boolean blocked;
        private final AgentInput input;

        private InboundOutcome(boolean blocked, AgentInput input) {
            this.blocked = blocked;
            this.input = input;
        }

        static InboundOutcome blocked() {
            return new InboundOutcome(true, null);
        }

        static InboundOutcome masked(AgentInput input) {
            return new InboundOutcome(false, input);
        }

        static InboundOutcome pass(AgentInput input) {
            return new InboundOutcome(false, input);
        }
    }

    /**
     * 一次调用的流式出站状态（每次 onAgent 新建一份，绝不放中间件字段——单例 Bean 上放可变状态，
     * 并发会话会互相串内容）。
     *
     * <p>{@code buffers} 按 blockId 分别缓冲：同一次回复可能有多个文本块（正文被工具调用打断后续写），
     * 各块的尾巴必须独立保留，混在一起会把 A 块的半个词接到 B 块开头。</p>
     */
    private static final class OutboundStreamState {
        private final Map<String, SensitiveWordStreamGuard> guards = new LinkedHashMap<>();
    }

    /**
     * 单条消息扫描结果：整体决策 + 打码后消息（仅 MASK）+ 最高档命中词 + 最高档命中所在文本块的过滤结果。
     *
     * <p>{@code topResult} 留着给命中日志用：一条消息可能有多个文本块，各自一份过滤结果，
     * 落日志只取决策最高的那块——它就是本次实际处置的依据，其原文片段与命中词才对得上运营看到的处置。</p>
     */
    private static final class MsgScan {
        private final SensitiveWordAction decision;
        private final Msg maskedMsg;
        private final SensitiveWord topWord;
        private final SensitiveWordFilterResult topResult;

        private MsgScan(SensitiveWordAction decision, Msg maskedMsg, SensitiveWord topWord,
                        SensitiveWordFilterResult topResult) {
            this.decision = decision;
            this.maskedMsg = maskedMsg;
            this.topWord = topWord;
            this.topResult = topResult;
        }

        static MsgScan pass() {
            return new MsgScan(null, null, null, null);
        }
    }
}
