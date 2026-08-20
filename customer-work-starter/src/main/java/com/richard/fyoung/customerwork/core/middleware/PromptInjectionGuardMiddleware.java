package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.regex.Pattern;
import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;

/**
 * 入站防注入围栏中间件（2.0 Middleware，「安全 · 提示词注入 / 越狱防护」）。
 *
 * <p>落在 {@link #onAgent} 段——这是 {@code MiddlewareBase} 唯一"拦截整次 Agent 调用"的钩子，
 * 天然适合做入站硬拦截：对 {@link AgentInput} 里的用户消息匹配注入/越狱模式，命中则<b>不调用
 * {@code next}</b>，直接返回统一拒绝话术，不产生模型调用（省成本 + 防止注入内容触达推理）。</p>
 *
 * <p>与 {@link ToolGuardMiddleware}（工具入参层，命中后<b>改写为安全占位继续放行</b>）刻意不同：
 * 一句已被识别为注入攻击的用户输入，没有"安全改写后继续对话"的合理中间态，因此这里是硬拦截而非改写。</p>
 *
 * <p>默认关闭。异常不打断主链路（原样放行，与本仓库其它护栏中间件一致的 fail-open 策略——
 * 围栏本身故障不应导致正常客服对话不可用）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class PromptInjectionGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuardMiddleware.class);

    private static final String M_BLOCKED = "customerwork.prompt.guard.blocked";
    private static final String CODE_INJECTION = "PROMPT-INJECTION-BLOCKED";

    private final boolean enabled;
    private final String refusalReply;
    /** 注入/越狱模式正则（预编译，不区分大小写）。 */
    private final List<Pattern> injectionPatterns;
    /** 命中拦截的累计次数（可观测用途，供单测断言 / 监控采样）。 */
    private final LongAdder blockedHits = new LongAdder();
    /** 可为 null：未接入 Micrometer 时观测降级为仅日志。 */
    private final MeterRegistry meterRegistry;

    @Autowired
    public PromptInjectionGuardMiddleware(CustomerWorkProperties properties,
                                          ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(properties.getHooks().getPromptGuard().isEnabled(),
            properties.getHooks().getPromptGuard().getRefusalReply(),
            properties.getHooks().getPromptGuard().getInjectionPatterns(),
            meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    /**
     * 参数化构造：供已排除 starter 自动装配的模块（如 customer-admin-server）显式装配。
     *
     * <p>没有这个构造时，admin 侧无法挂载本中间件——后台链路因此长期缺失入站注入防护，
     * 且连一个可开的配置项都没有。与 {@code IndirectInjectionGuardMiddleware} 的参数化构造同一用途。</p>
     */
    public PromptInjectionGuardMiddleware(boolean enabled, String refusalReply,
                                          List<String> patterns, MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.refusalReply = refusalReply;
        this.injectionPatterns = new ArrayList<>();
        if (patterns != null) {
            for (String p : patterns) {
                if (p != null && !p.isEmpty()) {
                    this.injectionPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
                }
            }
        }
        this.meterRegistry = meterRegistry;
    }

    /** 命中拦截的累计次数（供单测断言 / 监控采样）。 */
    long blockedHitCount() {
        return blockedHits.sum();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled || injectionPatterns.isEmpty()) {
            return next.apply(input);
        }
        try {
            String hitText = firstInjectionHit(input);
            if (hitText != null) {
                blockedHits.increment();
                if (meterRegistry != null) {
                    Counter.builder(M_BLOCKED).register(meterRegistry).increment();
                }
                log.error("[GUARD] inbound prompt injection blocked, code={}, agent={}",
                    CODE_INJECTION, agent == null ? "?" : agent.getName());
                return Flux.just(new AgentResultEvent(refusalMsg()));
            }
        } catch (Exception e) {
            log.error("[GUARD] prompt injection check failed (pass through), code={}", "PROMPT_GUARD_ERROR", e);
        }
        return next.apply(input);
    }

    /** 扫描全部输入消息，返回首个命中注入模式的文本；无命中返回 null。 */
    private String firstInjectionHit(AgentInput input) {
        if (input == null || input.msgs() == null) {
            return null;
        }
        for (Msg msg : input.msgs()) {
            if (msg == null) {
                continue;
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank() && matchesAny(text)) {
                return text;
            }
        }
        return null;
    }

    boolean matchesAny(String text) {
        for (Pattern p : injectionPatterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private Msg refusalMsg() {
        return Msg.builder()
            .role(MsgRole.ASSISTANT)
            .name("assistant")
            .textContent(refusalReply)
            .build();
    }
}
