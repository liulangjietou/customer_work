package com.richard.fyoung.customerwork.capability.typesafe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final JevCircuitBreaker breaker = new JevCircuitBreaker(3, 30_000, now::get);

    @Test
    @DisplayName("连续失败达到门槛才打开，中途成功会清零")
    void opensOnlyAfterConsecutiveFailures() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        breaker.onFailure();
        assertTrue(breaker.allowRequest(), "中途成功过一次，失败计数应当清零");

        assertTrue(breaker.onFailure(), "第三次连续失败应当报告状态翻转");
        assertFalse(breaker.allowRequest());
    }

    /**
     * 这条是熔断存在的意义：Jev 进入网络黑洞时，打开期间每一轮对话都应当立即降级，
     * 而不是各自干等一个超时。
     */
    @Test
    @DisplayName("打开期间一律跳过，窗口过后才放试探")
    void skipsWhileOpen() {
        trip();
        now.addAndGet(29_999);
        assertFalse(breaker.allowRequest());

        now.addAndGet(1);
        assertTrue(breaker.allowRequest(), "窗口过后应放一个试探");
    }

    @Test
    @DisplayName("半开只放一个试探，其余继续跳过")
    void halfOpenAllowsSingleProbe() {
        trip();
        now.addAndGet(30_000);

        assertTrue(breaker.allowRequest());
        assertFalse(breaker.allowRequest(), "高并发下若放行所有请求，会有一大批同时撞上超时");
        assertEquals(JevCircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    @DisplayName("试探成功整体恢复，试探失败立即重新打开")
    void probeOutcomeDecidesState() {
        trip();
        now.addAndGet(30_000);
        breaker.allowRequest();
        breaker.onSuccess();
        assertEquals(JevCircuitBreaker.State.CLOSED, breaker.state());

        trip();
        now.addAndGet(30_000);
        breaker.allowRequest();
        assertTrue(breaker.onFailure(), "半开态一次失败就应重新打开，不必再攒满门槛");
        assertFalse(breaker.allowRequest());
    }

    /**
     * 试探被下游取消（用户断开）时既没成功也没失败。不交还名额的话，
     * 状态会永远卡在半开，Jev 从此再也用不上——而且不报任何错。
     */
    @Test
    @DisplayName("试探被取消时交还名额，下一个请求能立即试探")
    void abandonedProbeReleasesSlot() {
        trip();
        now.addAndGet(30_000);
        assertTrue(breaker.allowRequest());

        breaker.onAbandoned();

        assertTrue(breaker.allowRequest(), "名额没交还：半开态被永久占住");
    }

    private void trip() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
    }
}
