package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 工具入参改写护栏的公共内核（无 Spring 依赖，供各模块的 {@code onActing} 护栏中间件组合复用）：
 * 承担两件所有护栏都一样的事——
 * <ul>
 *   <li><b>onActing 骨架</b>（{@link #guardActing}）：逐个工具调用交给调用方的改写函数，有改动才构造新的
 *       {@link ActingInput} 传给下游，异常一律原样放行不打断主链路；</li>
 *   <li><b>破坏性入参改写</b>（{@link #guardDestructive} / {@link #rewriteDestructiveParams}）：字符串入参命中
 *       破坏性判定即改写为安全占位，并累计命中次数供可观测使用。</li>
 * </ul>
 *
 * <p>规则来源不在本类：判定谓词由调用方传入（可以是自己编译的正则表，也可以委托
 * {@code ToolCallRiskDetector} 与人工确认闭环共用同一份规则），本类只负责改写动作与统计。
 * 日志前缀与错误码同样由调用方给定，保证各消费方原有的告警关键字不变。</p>
 * @author owlzhangfq@gmail.com
 */
public class ToolCallRewriteCore {

    private static final Logger log = LoggerFactory.getLogger(ToolCallRewriteCore.class);

    private final Predicate<String> destructiveMatcher;
    private final String placeholder;
    private final String logScope;
    private final String destructiveCode;
    private final String failureCode;
    /** 命中破坏性入参的累计次数（可观测用途，供告警指标 / 单测断言）。 */
    private final LongAdder destructiveHits = new LongAdder();

    /**
     * @param destructiveMatcher 字符串入参的破坏性判定
     * @param placeholder        命中后改写成的安全占位
     * @param logScope           日志前缀（区分消费方，如 {@code [GUARD]}）
     * @param destructiveCode    命中破坏性入参的错误码
     * @param failureCode        护栏自身异常（原样放行）的错误码
     */
    public ToolCallRewriteCore(Predicate<String> destructiveMatcher, String placeholder,
                               String logScope, String destructiveCode, String failureCode) {
        this.destructiveMatcher = destructiveMatcher;
        this.placeholder = placeholder;
        this.logScope = logScope;
        this.destructiveCode = destructiveCode;
        this.failureCode = failureCode;
    }

    /** 命中破坏性入参的累计次数（供监控采样 / 单测断言）。 */
    public long destructiveHitCount() {
        return destructiveHits.sum();
    }

    /**
     * onActing 骨架：逐个工具调用调用 {@code guard} 改写（返回 {@code null} 表示该调用无改动），
     * 任一调用有改动才把改写后的清单传给下游；护栏自身异常只记日志并原样放行，不打断主链路。
     */
    public Flux<AgentEvent> guardActing(ActingInput input, Function<ActingInput, Flux<AgentEvent>> next,
                                        UnaryOperator<ToolUseBlock> guard) {
        if (input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        try {
            List<ToolUseBlock> rewritten = new ArrayList<>(input.toolCalls().size());
            boolean anyChanged = false;
            for (ToolUseBlock use : input.toolCalls()) {
                ToolUseBlock guarded = guard.apply(use);
                if (guarded != null) {
                    rewritten.add(guarded);
                    anyChanged = true;
                } else {
                    rewritten.add(use);
                }
            }
            if (anyChanged) {
                return next.apply(new ActingInput(rewritten));
            }
        } catch (Exception e) {
            log.error("{} tool input guard failed (pass through), code={}", logScope, failureCode, e);
        }
        return next.apply(input);
    }

    /**
     * 只做破坏性入参改写的整块护栏（护栏本身没有其它改写动作时直接用这个）；
     * 无改动返回 {@code null}，与 {@link #guardActing} 的约定一致。
     */
    public ToolUseBlock guardDestructive(ToolUseBlock use) {
        if (use == null) {
            return null;
        }
        Map<String, Object> in = use.getInput() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(use.getInput());
        if (!rewriteDestructiveParams(use.getName(), in)) {
            return null;
        }
        return new ToolUseBlock(use.getId(), use.getName(), in, use.getMetadata());
    }

    /**
     * 就地改写入参 map 中命中破坏性判定的字符串值为安全占位并计数告警，返回是否发生改动。
     * 供还有其它改写动作（参数注入、数值钳制等）的护栏与自身动作组合在同一份 map 上。
     */
    public boolean rewriteDestructiveParams(String toolName, Map<String, Object> input) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(input).entrySet()) {
            Object raw = entry.getValue();
            if (raw instanceof CharSequence && destructiveMatcher.test(raw.toString())) {
                destructiveHits.increment();
                log.error("{} tool {} param {} hit destructive pattern, rewritten to placeholder, code={}",
                    logScope, toolName, entry.getKey(), destructiveCode);
                input.put(entry.getKey(), placeholder);
                changed = true;
            }
        }
        return changed;
    }
}
