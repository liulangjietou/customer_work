package com.richard.fyoung.customerwork.infra.counter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内窗口计数单测：固定窗口累加/回退/只读，滑动窗口取用与超限不记录。
 * @author owlzhangfq@gmail.com
 */
class InMemoryWindowCounterTest {

    private final InMemoryWindowCounter counter = new InMemoryWindowCounter();

    @Test
    void increment_shouldAccumulateWithinWindow() {
        assertEquals(5L, counter.increment("k", 5, 60), "首次累加应返回增量本身");
        assertEquals(8L, counter.increment("k", 3, 60), "同窗口内应继续累加");
    }

    @Test
    void decrement_shouldRollBackIncrement() {
        counter.increment("k", 10, 60);
        counter.decrement("k", 10, 60);
        assertEquals(0L, counter.current("k", 60), "被拒的请求不应继续占用额度");
    }

    @Test
    void current_shouldReturnZeroForUnknownKey() {
        assertEquals(0L, counter.current("never-used", 60), "没用过的键读出来必须是 0，不能是 null 或异常");
    }

    @Test
    void current_shouldNotCreateWindow() {
        counter.current("read-only", 60);
        assertEquals(3L, counter.increment("read-only", 3, 60),
            "只读路径不得产生写副作用，否则首次累加会读到被污染的基数");
    }

    @Test
    void increment_shouldIsolateDifferentKeys() {
        counter.increment("a", 100, 60);
        assertEquals(1L, counter.increment("b", 1, 60), "不同键的计数不能串");
    }

    @Test
    void tryAcquireSliding_shouldRejectBeyondLimit() {
        assertTrue(counter.tryAcquireSliding("s", 2, 60), "第 1 次应放行");
        assertTrue(counter.tryAcquireSliding("s", 2, 60), "第 2 次应放行");
        assertFalse(counter.tryAcquireSliding("s", 2, 60), "达到上限后应拒绝");
    }

    @Test
    void tryAcquireSliding_shouldNotRecordRejectedRequests() {
        counter.tryAcquireSliding("s", 1, 60);
        counter.tryAcquireSliding("s", 1, 60);
        counter.tryAcquireSliding("s", 1, 60);
        // 被拒的请求若也记录，持续打压会让窗口永远填满、再也恢复不了
        assertFalse(counter.tryAcquireSliding("s", 2, 60) && counter.tryAcquireSliding("s", 2, 60),
            "窗口内应只留下 1 条已放行记录，配额提到 2 后只能再放行 1 次");
    }

    @Test
    void tryAcquireSliding_shouldReleaseAfterWindowPasses() throws InterruptedException {
        assertTrue(counter.tryAcquireSliding("s", 1, 1), "窗口内第 1 次放行");
        assertFalse(counter.tryAcquireSliding("s", 1, 1), "窗口内第 2 次拒绝");
        Thread.sleep(1100);
        assertTrue(counter.tryAcquireSliding("s", 1, 1), "窗口滑过后应恢复放行");
    }

    @Test
    void slidingSum_shouldAccumulateAmounts() {
        assertEquals(300L, counter.incrementSlidingSum("tok", 300L, 1800));
        assertEquals(500L, counter.incrementSlidingSum("tok", 200L, 1800), "同窗口内累加的是量不是次数");
        assertEquals(500L, counter.currentSlidingSum("tok", 1800), "只读不产生副作用");
    }

    @Test
    void slidingSum_shouldSeparateKeys() {
        counter.incrementSlidingSum("a", 100L, 1800);
        assertEquals(0L, counter.currentSlidingSum("b", 1800), "不同计数键互不干扰");
    }

    @Test
    void slidingSum_shouldResetWhenWindowLengthChanges() {
        counter.incrementSlidingSum("tok", 100L, 1800);
        // 窗口长度变了，旧桶是按另一种口径切的，混着算出来的数没有意义
        assertEquals(0L, counter.currentSlidingSum("tok", 3600), "口径变更后按 0 计");
        assertEquals(50L, counter.incrementSlidingSum("tok", 50L, 3600), "下一次写入用新口径重开");
    }

    @Test
    void slidingSum_shouldReturnZero_forUnknownKey() {
        assertEquals(0L, counter.currentSlidingSum("never-written", 1800));
    }
}
