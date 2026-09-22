package com.richard.fyoung.customerwork.capability.typesafe;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Jev 调用熔断器。
 *
 * <p><b>为什么非有不可</b>：超时只能保证单次调用不会无限等待，保证不了「Jev 挂掉时对话照样快」。
 * 若 Jev 进入网络黑洞，没有熔断的话每一轮对话都要干等满一个超时才降级——用户感知到的是整个客服
 * 变慢了，而不是某个增强能力失效了。熔断打开后请求直接跳过，降级是即时的。</p>
 *
 * <p><b>半开只放一个试探</b>：打开窗口过后若放行所有请求，高并发下会有一大批请求同时撞上超时。
 * 这里只让抢到 CAS 的那一个去试探，其余继续跳过，试探成功才整体恢复。</p>
 *
 * <p>每个被放行的请求都必须以 {@link #onSuccess}、{@link #onFailure} 或 {@link #onAbandoned}
 * 之一收尾，否则半开态的试探名额会被永久占住，Jev 再也用不上。</p>
 */
public class JevCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openDurationMs;
    private final LongSupplier clock;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedAtMs;

    /**
     * @param failureThreshold 连续失败多少次后打开（&lt;=0 按 1 处理）
     * @param openDurationMs   打开后多久允许试探
     * @param clock            毫秒时钟，单测可注入
     */
    public JevCircuitBreaker(int failureThreshold, long openDurationMs, LongSupplier clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMs = Math.max(0, openDurationMs);
        this.clock = clock;
    }

    /** 本次是否允许发请求；半开态下只有一个调用方能拿到 true。 */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (clock.getAsLong() - openedAtMs < openDurationMs) {
                return false;
            }
            return state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }
        return false;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    /** @return 本次失败是否导致熔断打开（供调用方只在状态翻转时打一次日志） */
    public boolean onFailure() {
        if (state.get() == State.HALF_OPEN || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            trip();
            return true;
        }
        return false;
    }

    /** 请求被下游取消、既未成功也未失败：交还试探名额，下一个请求可以立即试探。 */
    public void onAbandoned() {
        if (state.get() == State.HALF_OPEN) {
            openedAtMs = clock.getAsLong() - openDurationMs;
            state.compareAndSet(State.HALF_OPEN, State.OPEN);
        }
    }

    State state() {
        return state.get();
    }

    private void trip() {
        openedAtMs = clock.getAsLong();
        consecutiveFailures.set(0);
        state.set(State.OPEN);
    }
}
