package com.richard.fyoung.customerwork.calllog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 缓存命中率口径单测。
 *
 * <p>这个口径最容易错在一个地方：{@code cachedTokens} 是 {@code inputTokens} 的<b>子集</b>，
 * 不是与输入并列的额外量。分母用错（比如拿 totalTokens）算出来的命中率会系统性偏低，
 * 进而得出"缓存没生效"的错误结论。故把它固定在领域对象上并用测试锁住。</p>
 * @author owlzhangfq@gmail.com
 */
class CacheHitRateTest {

    private AgentCallRecord recordWith(Long inputTokens, Long cachedTokens) {
        return new AgentCallRecord(1L, "req", "u", "user", "agent", "客服", "sess",
            AgentCallSessionType.CHAT, "问", "答", 0L, 100L, 100L, 60L, 0L, 0L, 0L, 1,
            inputTokens, 50L, inputTokens == null ? null : inputTokens + 50L,
            cachedTokens, 55L, true, null, java.util.List.of());
    }

    @Test
    void record_cacheHitRate_shouldDivideByInputNotTotal() {
        // 输入 1000、缓存 800、输出 50（总 1050）：命中率必须是 800/1000 而不是 800/1050
        AgentCallRecord record = recordWith(1000L, 800L);

        assertEquals(0.8d, record.cacheHitRate(), 1e-9, "分母是输入总量，缓存量是它的子集");
    }

    @Test
    void record_cacheHitRate_shouldBeNullWhenNotCollected() {
        assertNull(recordWith(1000L, null).cacheHitRate(), "未采到缓存量时不能当作 0——那是两回事");
        assertNull(recordWith(null, 800L).cacheHitRate(), "没有输入量就没有分母");
    }

    @Test
    void record_cacheHitRate_shouldBeNullWhenInputIsZero() {
        assertNull(recordWith(0L, 0L).cacheHitRate(), "输入为 0 时不做除法");
    }

    @Test
    void summary_cacheHitRate_shouldComputeFromAggregates() {
        AgentCallLogSummary summary = new AgentCallLogSummary(
            10L, 100d, 200L, 60d, 10d, 5d, 5d, 10500L, 1050d, 10000L, 8000L);

        assertEquals(0.8d, summary.cacheHitRate(), 1e-9);
    }

    @Test
    void summary_cacheHitRate_shouldBeZeroWhenNoInput() {
        assertEquals(0d, AgentCallLogSummary.empty().cacheHitRate(), 1e-9);
    }
}
