package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;

/**
 * MDC 上下文织入装配（Reactor Context → SLF4J MDC）。
 *
 * <p>通过 {@code Hooks.onEachOperator} 在全局所有 Reactor 操作符上织入 {@link MdcContextLifter}，
 * 使反应式链路上任意线程的日志自动带上 requestId / sessionId，无需业务代码逐处 {@code doOnEach} 打点。
 * 仅当 {@code customer-work.observability.mdc-enabled=true}（默认）时注册。</p>
 *
 * <p>说明：{@code onEachOperator} 是 JVM 级全局 Hook，对每个操作符有极小开销；换取的是全链路
 * 日志关联能力——线上故障定位收益远大于该开销，故默认开启，可按需关闭。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class MdcConfig {

    private static final Logger log = LoggerFactory.getLogger(MdcConfig.class);

    /** Hook 注册键：便于按键卸载，避免与其它库的 Hook 冲突。 */
    private static final String HOOK_KEY = "customerwork-mdc-context-lifter";

    private final boolean enabled;

    public MdcConfig(CustomerWorkProperties properties) {
        this.enabled = properties.getObservability().isMdcEnabled();
    }

    @PostConstruct
    public void register() {
        if (!enabled) {
            log.info("[MDC] reactor context->MDC lifter disabled by config");
            return;
        }
        Hooks.onEachOperator(HOOK_KEY,
            Operators.lift((scannable, subscriber) -> new MdcContextLifter<>(subscriber)));
        log.info("[MDC] reactor context->MDC lifter registered, keys={}", MdcContextLifter.MDC_KEYS);
    }

    @PreDestroy
    public void unregister() {
        if (enabled) {
            Hooks.resetOnEachOperator(HOOK_KEY);
            log.info("[MDC] reactor context->MDC lifter unregistered");
        }
    }
}
