package com.richard.fyoung.customerwork.core.model.failover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型熔断状态登记表：按候选标识记录连续失败次数与熔断到期时刻。
 *
 * <p>语义：{@link #recordFailure} 累计连续失败，达到阈值即打开熔断（{@code openUntil=now+时长}）；
 * {@link #recordSuccess} 清零；{@link #isOpen} 判断当前是否处于熔断中，熔断到期自动视为半开
 * （重置计数、放行一次尝试）。手写实现，不引 Resilience4j——只需"连续失败计数 + 时间窗"这点语义。</p>
 *
 * <p>阈值与时长在构造时定死（纯入参，不绑定任何配置类）：admin 侧由 {@code admin.model.failover.*}
 * 配置属性传入，starter 自身的模型链由 {@code ModelConfig} 传入常量。本类<b>不注册为 Bean</b>，
 * 由使用方按需 new 或在自己模块里包一层 Spring 组件。</p>
 * @author owlzhangfq@gmail.com
 */
public class ModelCircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelCircuitBreakerRegistry.class);

    /** 熔断打开瞬间的日志错误码。 */
    private static final String CODE_BREAKER_OPEN = "MODEL-BREAKER-OPEN";

    private static final long MILLIS_PER_SECOND = 1000L;

    private final int failureThreshold;
    private final int openDurationSeconds;
    private final ConcurrentHashMap<Long, BreakerState> states = new ConcurrentHashMap<>();

    /**
     * @param failureThreshold    连续失败达到该次数即打开熔断
     * @param openDurationSeconds 熔断打开后的持续秒数，期间该候选被跳过
     */
    public ModelCircuitBreakerRegistry(int failureThreshold, int openDurationSeconds) {
        this.failureThreshold = failureThreshold;
        this.openDurationSeconds = openDurationSeconds;
    }

    /** 记录一次失败：连续失败达阈值则打开熔断（仅在打开瞬间记一次 error 日志）。 */
    public void recordFailure(Long modelId) {
        BreakerState state = states.computeIfAbsent(modelId, k -> new BreakerState());
        synchronized (state) {
            state.consecutiveFailures++;
            boolean alreadyOpen = state.openUntil > System.currentTimeMillis();
            if (state.consecutiveFailures >= failureThreshold && !alreadyOpen) {
                state.openUntil = System.currentTimeMillis() + openDurationSeconds * MILLIS_PER_SECOND;
                log.error("model circuit breaker opened, code={}, modelId={}, failures={}, openSeconds={}",
                    CODE_BREAKER_OPEN, modelId, state.consecutiveFailures, openDurationSeconds);
            }
        }
    }

    /** 记录一次成功：清空该模型的失败计数与熔断状态。 */
    public void recordSuccess(Long modelId) {
        BreakerState state = states.get(modelId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.openUntil = 0L;
        }
    }

    /** 是否熔断中；已到期则半开：重置计数并放行（返回 false）。 */
    public boolean isOpen(Long modelId) {
        BreakerState state = states.get(modelId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.openUntil <= 0L) {
                return false;
            }
            if (System.currentTimeMillis() < state.openUntil) {
                return true;
            }
            // 熔断窗口已过，进入半开：重置后放行一次尝试
            state.consecutiveFailures = 0;
            state.openUntil = 0L;
            return false;
        }
    }

    /** 单模型熔断状态；所有读写均在持有该对象锁时进行。 */
    private static final class BreakerState {
        private int consecutiveFailures;
        private long openUntil;
    }
}
