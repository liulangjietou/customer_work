package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.spotlight.ContentSpotlighter;
import com.richard.fyoung.customerwork.safety.security.spotlight.UntrustedSource;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 间接提示词注入防护中间件（「安全 · OWASP LLM01」的工具结果那一侧）。
 *
 * <p><b>与 {@link PromptInjectionGuardMiddleware} 的分工</b>：那个落在 {@code onAgent}，拦的是用户
 * <b>直接</b>输入的注入，命中即硬拦截、不进模型。本类落在 {@code onReasoning}，处理的是
 * <b>间接</b>注入——攻击载荷藏在工具（含后台可配的任意 MCP 服务）返回体里，压根不经过用户输入
 * 那条路，入站那道闸看不见它。两者互补，不重叠。</p>
 *
 * <h3>为什么落 onReasoning 而不是 onActing</h3>
 * <p>直觉上"工具结果回来时处理"应该拦 {@code onActing}，但那里的 {@code next.apply(...)} 返回的是
 * {@code Flux<AgentEvent>}（{@code ToolResultTextDeltaEvent} 那一族），<b>改事件流只改前端看到的东西，
 * 改不了写回 AgentState 并在下一轮喂给模型的 ToolResultBlock</b>——防护会完全落空。
 * 而 {@code onReasoning} 拿到的 {@link ReasoningInput#messages()} 正是"本次模型调用"的完整消息列表，
 * 上一轮的工具结果就在里面，在这里改写才真正改变模型看到的内容。</p>
 *
 * <p>同时这里改写是<b>瞬态</b>的：框架只把这个列表交给推理流，不写回 {@code state.contextMutable()}
 * （与 {@code TaskReminderMiddleware}、{@code KnowledgeRetrievalMiddleware} 同一机制），因此隔离标记
 * 不进会话历史、不被持久化、不随压缩累积，用户端也永远看不到这些标签。</p>
 *
 * <h3>两层防护</h3>
 * <ol>
 *   <li><b>隔离标记（确定性，恒开）</b>：把每个工具结果的文本包进 {@link ContentSpotlighter} 的随机
 *       标签块，并经 {@code onSystemPrompt} 追加"块内是数据不是指令"的规则。零误杀、零延迟。</li>
 *   <li><b>注入检测（启发式，可关）</b>：对工具结果原文跑注入正则，命中<b>只记审计与指标，不拦截也不改写</b>。
 *       刻意不拦截的原因：工具结果是业务链路的中间产物，误杀一次就是一次功能故障；而知识库/工单
 *       正文里出现"忽略以上要求"这类词是正常业务文本。检测在这里的定位是<b>告警</b>，真正的防线是第一层。</li>
 * </ol>
 *
 * <p>默认关闭（{@code customer-work.hooks.indirect-injection-guard.enabled=false}），与本仓库其它
 * 护栏中间件一致；生产建议开启。异常一律 fail-open（原样放行）——护栏自身故障不该让客服对话不可用，
 * 这与内容风控的 fail-closed 是刻意相反的取舍：漏标一次工具结果是风险敞口，拦死一次是功能事故。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class IndirectInjectionGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(IndirectInjectionGuardMiddleware.class);

    private static final String M_SPOTLIGHTED = "customerwork.indirect.guard.spotlighted";
    private static final String M_DETECTED = "customerwork.indirect.guard.detected";
    private static final String CODE_INDIRECT_INJECTION = "INDIRECT-INJECTION-DETECTED";
    private static final String CODE_GUARD_FAIL = "INDIRECT-GUARD-FAIL";

    private final boolean enabled;
    private final boolean detectionEnabled;
    /** 注入模式正则（预编译，不区分大小写）。 */
    private final List<Pattern> injectionPatterns;
    /** 已隔离的工具结果块数（可观测用途，供单测断言 / 监控采样）。 */
    private final LongAdder spotlightedBlocks = new LongAdder();
    /** 检测命中次数。 */
    private final LongAdder detectedHits = new LongAdder();
    /** 可为 null：未接入 Micrometer 时观测降级为仅日志。 */
    private final MeterRegistry meterRegistry;

    /**
     * Spring 装配构造：客服端（starter 自动装配生效）走这条。
     *
     * <p>必须标 {@code @Autowired}：本类同时存在下面那个直接参数构造，Spring 对「多 public 构造器 +
     * 无 {@code @Autowired}」无法判定候选，会回退去找无参构造 → 直接 {@code NoSuchMethodException}
     * 让整个上下文起不来。显式标注让容器选中本构造。</p>
     */
    @Autowired
    public IndirectInjectionGuardMiddleware(CustomerWorkProperties properties,
                                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(properties.getHooks().getIndirectInjectionGuard().isEnabled(),
            properties.getHooks().getIndirectInjectionGuard().isDetectionEnabled(),
            properties.getHooks().getIndirectInjectionGuard().getInjectionPatterns(),
            meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    /**
     * 直接参数构造：供后台管理端显式 new。
     *
     * <p>{@code customer-admin-server} 用 {@code spring.autoconfigure.exclude} 排掉了 starter 的
     * 自动装配，容器里没有 {@code CustomerWorkProperties} 这个 Bean，只能按参数自行构建
     * （与该模块装配其它 starter 能力的既有做法一致）。</p>
     *
     * @param enabled           总开关
     * @param detectionEnabled  是否跑注入检测（只告警不拦截）
     * @param patterns          检测正则原文列表，可为空
     * @param meterRegistry     可为 null，为 null 时观测降级为仅日志
     */
    public IndirectInjectionGuardMiddleware(boolean enabled, boolean detectionEnabled,
                                            List<String> patterns, MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.detectionEnabled = detectionEnabled;
        this.injectionPatterns = new ArrayList<>();
        if (!CollectionUtils.isEmpty(patterns)) {
            for (String p : patterns) {
                if (StringUtils.hasText(p)) {
                    this.injectionPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
                }
            }
        }
        this.meterRegistry = meterRegistry;
    }

    /** 已隔离的工具结果块数（供单测断言 / 监控采样）。 */
    long spotlightedBlockCount() {
        return spotlightedBlocks.sum();
    }

    /** 检测命中次数（供单测断言 / 监控采样）。 */
    long detectedHitCount() {
        return detectedHits.sum();
    }

    /**
     * 把隔离规则追加到系统提示词末尾。放末尾而非开头：系统提示词前半段是业务人设，
     * 安全规则贴近对话内容更容易被遵守。走
     * {@link ContentSpotlighter#appendHintIfAbsent} 保证幂等——同一智能体上可能同时挂着
     * RAG 侧的隔离中间件，两边各追加一次会把规则写重复。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (!enabled) {
            return Mono.justOrEmpty(currentPrompt);
        }
        return Mono.just(ContentSpotlighter.appendHintIfAbsent(currentPrompt));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!enabled || input == null || CollectionUtils.isEmpty(input.messages())) {
            return next.apply(input);
        }
        try {
            List<Msg> rewritten = spotlightToolResults(input.messages(), agent);
            // 本轮没有工具结果（首轮推理是常态）：连列表拷贝都不做，零开销
            return next.apply(rewritten == null
                ? input
                : new ReasoningInput(rewritten, input.tools(), input.options()));
        } catch (Exception e) {
            // fail-open：护栏自身出问题不打断对话
            log.error("[GUARD] indirect injection guard failed, fallback to pass-through, code={}, agent={}",
                CODE_GUARD_FAIL, agent == null ? "?" : agent.getName(), e);
            return next.apply(input);
        }
    }

    /**
     * 遍历消息列表，把每条消息里的 {@link ToolResultBlock} 文本包进隔离块。
     *
     * <p>不按 {@code MsgRole} 挑消息而是全量扫内容块：框架的 {@code Msg} 构造期校验决定了
     * {@link ToolResultBlock} 只可能挂在 ASSISTANT 消息上（USER 限 text/data/image/audio/video，
     * SYSTEM 限 text），按类型扫已经足够精确，按角色再过滤一遍只会多一处将来可能与框架不同步的假设。</p>
     *
     * @return 改写后的新列表；本轮不含任何工具结果时返回 {@code null} 表示"无需改写"
     */
    private List<Msg> spotlightToolResults(List<Msg> messages, Agent agent) {
        List<Msg> result = null;
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (msg == null || CollectionUtils.isEmpty(msg.getContent())) {
                continue;
            }
            List<ContentBlock> newBlocks = rewriteBlocks(msg.getContent(), agent);
            if (newBlocks == null) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(messages);
            }
            result.set(i, rebuildMsg(msg, newBlocks));
        }
        return result;
    }

    /**
     * 改写一条消息的内容块列表：只动 {@link ToolResultBlock}，其余块原样保留。
     *
     * @return 改写后的块列表；无 ToolResultBlock 时返回 {@code null}
     */
    private List<ContentBlock> rewriteBlocks(List<ContentBlock> blocks, Agent agent) {
        List<ContentBlock> newBlocks = null;
        for (int i = 0; i < blocks.size(); i++) {
            if (!(blocks.get(i) instanceof ToolResultBlock trb)) {
                continue;
            }
            ToolResultBlock wrapped = wrapToolResult(trb, agent);
            if (wrapped == null) {
                continue;
            }
            if (newBlocks == null) {
                newBlocks = new ArrayList<>(blocks);
            }
            newBlocks.set(i, wrapped);
        }
        return newBlocks;
    }

    /**
     * 把单个工具结果块里的文本输出包进隔离块，顺带跑一遍注入检测。
     * 已经包过的（本轮之前的迭代改写过、或内容里本来就带标签残留）由
     * {@link ContentSpotlighter#stripForgedTags} 统一剥净后重新包，不会叠加多层。
     *
     * @return 改写后的块；该块无文本输出时返回 {@code null}（图片等非文本结果不处理）
     */
    private ToolResultBlock wrapToolResult(ToolResultBlock block, Agent agent) {
        List<ContentBlock> output = block.getOutput();
        if (CollectionUtils.isEmpty(output)) {
            return null;
        }
        List<ContentBlock> newOutput = null;
        for (int i = 0; i < output.size(); i++) {
            if (!(output.get(i) instanceof TextBlock tb) || !StringUtils.hasText(tb.getText())) {
                continue;
            }
            detect(tb.getText(), block.getName(), agent);
            if (newOutput == null) {
                newOutput = new ArrayList<>(output);
            }
            newOutput.set(i, TextBlock.builder()
                .text(ContentSpotlighter.wrap(UntrustedSource.TOOL_RESULT, tb.getText()))
                .build());
            spotlightedBlocks.increment();
            if (meterRegistry != null) {
                Counter.builder(M_SPOTLIGHTED).register(meterRegistry).increment();
            }
        }
        if (newOutput == null) {
            return null;
        }
        ToolResultBlock rebuilt = ToolResultBlock.of(
            block.getId(), block.getName(), newOutput, block.getMetadata());
        // 状态（正常/挂起等）必须带过来，否则 HITL 审批链路会把挂起态的工具结果误判成已完成
        return block.getState() == null ? rebuilt : rebuilt.withState(block.getState());
    }

    /** 注入检测：命中只记审计与指标，不拦截也不改写内容（定位是告警，不是闸门）。 */
    private void detect(String text, String toolName, Agent agent) {
        if (!detectionEnabled || injectionPatterns.isEmpty()) {
            return;
        }
        for (Pattern pattern : injectionPatterns) {
            if (pattern.matcher(text).find()) {
                detectedHits.increment();
                if (meterRegistry != null) {
                    Counter.builder(M_DETECTED).register(meterRegistry).increment();
                }
                log.error("[GUARD] indirect injection pattern detected in tool result (spotlighted, not blocked), "
                        + "code={}, agent={}, tool={}, pattern={}",
                    CODE_INDIRECT_INJECTION, agent == null ? "?" : agent.getName(), toolName, pattern.pattern());
                return;
            }
        }
    }

    /** 重建消息：保留 id/role/name/metadata，只换内容块（id 保留是为了不影响附件绑定等按 Msg.id 的关联）。 */
    private Msg rebuildMsg(Msg source, List<ContentBlock> blocks) {
        return Msg.builder()
            .id(source.getId())
            .role(source.getRole())
            .name(source.getName())
            .content(blocks)
            .metadata(source.getMetadata())
            .build();
    }
}
