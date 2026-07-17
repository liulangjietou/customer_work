package com.richard.fyoung.customerwork.middleware;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWord;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordFilterResult;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordHit;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
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

    /** 入站命中 BLOCK 的累计次数（供单测断言 / 监控采样）。 */
    private final LongAdder inboundBlockedHits = new LongAdder();

    public SensitiveWordMiddleware(CustomerWorkProperties properties,
                                   SensitiveWordFilter filter,
                                   AuditSink auditSink,
                                   ObjectProvider<MeterRegistry> meterRegistryProvider) {
        CustomerWorkProperties.SensitiveWord cfg = properties.getSensitiveWord();
        this.enabled = cfg.isEnabled();
        this.inboundEnabled = cfg.inboundEnabled();
        this.outboundEnabled = cfg.outboundEnabled();
        this.inboundSafeReply = cfg.getInboundSafeReply();
        this.outboundSafeReply = cfg.getOutboundSafeReply();
        this.filter = filter;
        this.auditSink = auditSink;
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
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
                InboundOutcome outcome = filterInbound(agent, input);
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
            downstream = downstream.map(event -> filterOutbound(agent, event));
        }
        return downstream;
    }

    // ---------------- 入站 ----------------

    private InboundOutcome filterInbound(Agent agent, AgentInput input) {
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
                audit("sensitive-inbound-block", agent, scan.topWord);
                return InboundOutcome.blocked();
            }
            if (scan.decision == SensitiveWordAction.MASK && scan.maskedMsg != null) {
                metric(M_INBOUND_MASKED);
                audit("sensitive-inbound-mask", agent, scan.topWord);
                rebuilt.add(scan.maskedMsg);
                changed = true;
            } else {
                if (scan.decision == SensitiveWordAction.REVIEW) {
                    audit("sensitive-inbound-review", agent, scan.topWord);
                }
                rebuilt.add(msg);
            }
        }
        return changed ? InboundOutcome.masked(new AgentInput(rebuilt)) : InboundOutcome.pass(input);
    }

    // ---------------- 出站 ----------------

    private AgentEvent filterOutbound(Agent agent, AgentEvent event) {
        if (!(event instanceof AgentResultEvent result)) {
            return event;
        }
        try {
            MsgScan scan = scanMsg(result.getResult());
            if (scan.decision == SensitiveWordAction.BLOCK) {
                metric(M_OUTBOUND_BLOCKED);
                log.info("[SENSITIVE] outbound blocked, category={}, word={}, agent={}",
                    categoryOf(scan.topWord), wordOf(scan.topWord), agentName(agent));
                audit("sensitive-outbound-block", agent, scan.topWord);
                return new AgentResultEvent(safeMsg(outboundSafeReply));
            }
            if (scan.decision == SensitiveWordAction.MASK && scan.maskedMsg != null) {
                metric(M_OUTBOUND_MASKED);
                audit("sensitive-outbound-mask", agent, scan.topWord);
                return new AgentResultEvent(scan.maskedMsg);
            }
            if (scan.decision == SensitiveWordAction.REVIEW) {
                audit("sensitive-outbound-review", agent, scan.topWord);
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
        List<ContentBlock> rebuilt = new ArrayList<>(blocks.size());
        boolean maskedAny = false;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                SensitiveWordFilterResult res = filter.check(tb.getText());
                if (res.hasHit()) {
                    if (topDecision == null || res.decision().severity() > topDecision.severity()) {
                        topDecision = res.decision();
                        topWord = strongestWord(res);
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
        return new MsgScan(topDecision, maskedMsg, topWord);
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

    /** 单条消息扫描结果：整体决策 + 打码后消息（仅 MASK）+ 最高档命中词。 */
    private static final class MsgScan {
        private final SensitiveWordAction decision;
        private final Msg maskedMsg;
        private final SensitiveWord topWord;

        private MsgScan(SensitiveWordAction decision, Msg maskedMsg, SensitiveWord topWord) {
            this.decision = decision;
            this.maskedMsg = maskedMsg;
            this.topWord = topWord;
        }

        static MsgScan pass() {
            return new MsgScan(null, null, null);
        }
    }
}
