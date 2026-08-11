package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型成本熔断器单测：分钟限额 / 小时限额 / 禁用时放行 / 回滚。
 * @author owlzhangfq@gmail.com
 */
class ModelCostCircuitBreakerTest {

    private CustomerWorkProperties props(boolean enabled, int perMin, int perHour) {
        CustomerWorkProperties p = new CustomerWorkProperties();
        p.getModel().getCostControl().setEnabled(enabled);
        p.getModel().getCostControl().setMaxTokensPerMinute(perMin);
        p.getModel().getCostControl().setMaxTokensPerHour(perHour);
        return p;
    }

    @Test
    void shouldAllow_whenDisabled() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(false, 100, 1000));
        assertTrue(breaker.tryConsume(1000));
        assertTrue(breaker.tryConsume(10000));
        assertFalse(breaker.isCircuitOpen());
    }

    @Test
    void shouldAllowWithinMinuteLimit() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(true, 1000, 10000));
        assertTrue(breaker.tryConsume(500));
        assertTrue(breaker.tryConsume(400));
        assertEquals(900, breaker.getMinuteTokens());
    }

    @Test
    void shouldBlock_whenMinuteLimitExceeded() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(true, 1000, 100000));
        assertTrue(breaker.tryConsume(800));
        assertTrue(breaker.tryConsume(200));
        // 超过分钟限额
        assertFalse(breaker.tryConsume(100));
        // 回滚后实际消耗应不变（1000）
        assertEquals(1000, breaker.getMinuteTokens());
    }

    @Test
    void shouldBlock_whenHourLimitExceeded() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(true, 100000, 5000));
        assertTrue(breaker.tryConsume(3000));
        assertTrue(breaker.tryConsume(1900));
        // 超过小时限额（4900 + 200 = 5100 > 5000）
        assertFalse(breaker.tryConsume(200));
        // 回滚后小时消耗应不变（4900）
        assertEquals(4900, breaker.getHourTokens());
    }

    @Test
    void shouldRollbackBothWindows_whenHourLimitExceeded() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(true, 10000, 5000));
        breaker.tryConsume(4900);
        // 超过小时限额时，分钟和小时窗口都应回滚
        assertFalse(breaker.tryConsume(200));
        assertEquals(4900, breaker.getMinuteTokens(), "分钟窗口应回滚");
        assertEquals(4900, breaker.getHourTokens(), "小时窗口应回滚");
    }

    @Test
    void shouldAllowZeroTokens() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(true, 100, 1000));
        assertTrue(breaker.tryConsume(0), "0 token 应放行");
        assertEquals(0, breaker.getMinuteTokens());
    }

    @Test
    void shouldReportCircuitOpen() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(true, 100, 1000));
        breaker.tryConsume(100);
        // 已消耗达到分钟上限
        assertTrue(breaker.isCircuitOpen());
    }

    @Test
    void shouldNotReportCircuitOpen_whenDisabled() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker(props(false, 100, 1000));
        breaker.tryConsume(1000);
        assertFalse(breaker.isCircuitOpen());
    }

    @Test
    void defaultConstructor_shouldBeDisabled() {
        ModelCostCircuitBreaker breaker = new ModelCostCircuitBreaker();
        assertTrue(breaker.tryConsume(Integer.MAX_VALUE));
        assertFalse(breaker.isCircuitOpen());
    }
}
