package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自带线程的刷新驱动单测：按间隔重复触发 / 刷新抛异常不中断后续轮次 / stop 后停止调度 / 非法间隔 fast-fail。
 *
 * <p>用短间隔 + {@link CountDownLatch} 等待事件本身，不写 sleep 断言，避免时序脆弱。</p>
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordRefreshDriverTest {

    /** 轮询间隔：足够短让测试跑得快，又不至于把 CI 机器压出抖动。 */
    private static final long INTERVAL_MS = 50L;

    /** 等待若干轮刷新的上限；正常路径远早于此返回。 */
    private static final long AWAIT_TIMEOUT_MS = 5000L;

    /** 断言"不再发生"的观察窗口：覆盖多个轮询周期。 */
    private static final long SILENCE_WINDOW_MS = 400L;

    private static final SensitiveWordStore STUB_STORE = new InMemorySensitiveWordStore();

    private static final SensitiveWordFilter STUB_FILTER =
        new SensitiveWordFilter(STUB_STORE, '*', SensitiveWordAction.BLOCK);

    /** 只记录调用次数的刷新器：驱动只关心"有没有按点被调到"，真实刷新语义由 SensitiveWordRefresherTest 覆盖。 */
    private static final class RecordingRefresher extends SensitiveWordRefresher {

        private final AtomicInteger invocations = new AtomicInteger();
        private final boolean explode;
        private volatile CountDownLatch latch;

        RecordingRefresher(int expectedRounds, boolean explode) {
            super(STUB_STORE, STUB_FILTER, false);
            this.latch = new CountDownLatch(expectedRounds);
            this.explode = explode;
        }

        @Override
        public boolean refreshOnce() {
            invocations.incrementAndGet();
            latch.countDown();
            if (explode) {
                throw new IllegalStateException("refresh boom");
            }
            return true;
        }

        void expect(int rounds) {
            this.latch = new CountDownLatch(rounds);
        }

        boolean await(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        int invocations() {
            return invocations.get();
        }
    }

    @Test
    void shouldRefreshRepeatedly_onFixedInterval() throws InterruptedException {
        RecordingRefresher refresher = new RecordingRefresher(3, false);
        SensitiveWordRefreshDriver driver = new SensitiveWordRefreshDriver(refresher, INTERVAL_MS);
        try {
            assertTrue(refresher.await(AWAIT_TIMEOUT_MS), "驱动应按间隔重复触发刷新");
            assertTrue(driver.isRunning(), "正常轮次不应停掉定时器");
        } finally {
            driver.stop();
        }
    }

    @Test
    void shouldKeepScheduling_whenRefreshThrows() throws InterruptedException {
        // 每轮都抛：scheduleWithFixedDelay 不吞异常的话，第一轮抛出后后续会被静默取消
        RecordingRefresher refresher = new RecordingRefresher(3, true);
        SensitiveWordRefreshDriver driver = new SensitiveWordRefreshDriver(refresher, INTERVAL_MS);
        try {
            assertTrue(refresher.await(AWAIT_TIMEOUT_MS), "刷新抛异常不应中断后续轮次");
            assertTrue(driver.isRunning(), "异常轮次后定时器仍应存活");
        } finally {
            driver.stop();
        }
    }

    @Test
    void shouldStopScheduling_afterStop() throws InterruptedException {
        RecordingRefresher refresher = new RecordingRefresher(1, false);
        SensitiveWordRefreshDriver driver = new SensitiveWordRefreshDriver(refresher, INTERVAL_MS);
        assertTrue(refresher.await(AWAIT_TIMEOUT_MS), "停止前应先跑起来");

        driver.stop();
        int stoppedAt = refresher.invocations();
        refresher.expect(1);

        assertFalse(refresher.await(SILENCE_WINDOW_MS), "stop 之后不应再有新一轮刷新");
        assertEquals(stoppedAt, refresher.invocations(), "stop 之后调用次数不应再增长");
        assertFalse(driver.isRunning(), "stop 之后定时器应处于关闭态");

        // 幂等：重复 stop 不应抛异常
        driver.stop();
    }

    @Test
    void shouldFailFast_onIllegalInterval() {
        RecordingRefresher refresher = new RecordingRefresher(1, false);
        assertThrows(IllegalArgumentException.class,
            () -> new SensitiveWordRefreshDriver(refresher, 0L), "非法间隔应在构造期炸出来");
        assertThrows(IllegalArgumentException.class,
            () -> new SensitiveWordRefreshDriver(null, INTERVAL_MS), "缺刷新器应在构造期炸出来");
    }
}
