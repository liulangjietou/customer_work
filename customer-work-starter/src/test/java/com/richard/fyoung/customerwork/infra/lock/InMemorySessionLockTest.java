package com.richard.fyoung.customerwork.infra.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内会话锁单测：同会话互斥、不同会话并行、等待超时、锁对象按使用者计数回收。
 * @author owlzhangfq@gmail.com
 */
class InMemorySessionLockTest {

    @Test
    void acquire_shouldSerializeSameSession() throws Exception {
        InMemorySessionLock lock = new InMemorySessionLock(5);
        SessionLock.Releasable first = lock.acquire("s1");

        AtomicBoolean secondEntered = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            SessionLock.Releasable second = lock.acquire("s1");
            secondEntered.set(true);
            second.release();
        });
        t.start();
        started.await();
        Thread.sleep(100);

        assertFalse(secondEntered.get(), "同一会话的第二个请求必须等前一个释放");
        first.release();
        t.join(2000);
        assertTrue(secondEntered.get(), "前一个释放后第二个应立即进入");
    }

    @Test
    void acquire_shouldNotBlockDifferentSessions() {
        InMemorySessionLock lock = new InMemorySessionLock(1);
        SessionLock.Releasable a = lock.acquire("s1");
        SessionLock.Releasable b = lock.acquire("s2");
        a.release();
        b.release();
        // 不同会话互不阻塞：若实现退化成全局锁，上面第二次 acquire 会等到超时抛异常
    }

    @Test
    void acquire_shouldTimeoutWhenHeldTooLong() {
        InMemorySessionLock lock = new InMemorySessionLock(1);
        lock.acquire("s1");
        assertThrows(SessionLockTimeoutException.class, () -> lock.acquire("s1"),
            "等不到锁应抛超时而不是无限阻塞，否则请求线程会堆积到耗尽");
    }

    @Test
    void release_shouldEvictLockEntry() {
        InMemorySessionLock lock = new InMemorySessionLock(5);
        SessionLock.Releasable r1 = lock.acquire("s1");
        SessionLock.Releasable r2 = lock.acquire("s2");
        assertEquals(2, lock.trackedSessions(), "持有期间锁对象应存在");

        r1.release();
        r2.release();
        // 改造前每见一个新 sessionId 就留一个 Semaphore 且永不移除，长跑必然堆成内存泄漏
        assertEquals(0, lock.trackedSessions(), "全部释放后锁对象应被回收");
    }

    @Test
    void acquire_shouldEvictEntryOnTimeout() {
        InMemorySessionLock lock = new InMemorySessionLock(1);
        SessionLock.Releasable held = lock.acquire("s1");
        assertThrows(SessionLockTimeoutException.class, () -> lock.acquire("s1"));
        held.release();
        assertEquals(0, lock.trackedSessions(), "超时失败的等待者也要把自己的计数退掉，否则条目永远回收不掉");
    }

    @Test
    void acquire_shouldSupportSequentialReuse() throws Exception {
        InMemorySessionLock lock = new InMemorySessionLock(5);
        for (int i = 0; i < 3; i++) {
            SessionLock.Releasable r = lock.acquire("s1");
            r.release();
        }
        assertEquals(0, lock.trackedSessions(), "反复获取释放不应残留条目");
        assertTrue(lock.acquire("s1") != null, "条目被回收后仍应能重新获取");
    }

    @Test
    void unlimitedWait_shouldBlockUntilReleased() throws Exception {
        InMemorySessionLock lock = new InMemorySessionLock(0);
        SessionLock.Releasable first = lock.acquire("s1");
        CountDownLatch acquired = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            lock.acquire("s1").release();
            acquired.countDown();
        });
        t.start();
        assertFalse(acquired.await(200, TimeUnit.MILLISECONDS), "waitSeconds<=0 表示不限等待，不应提前超时");
        first.release();
        assertTrue(acquired.await(2, TimeUnit.SECONDS), "释放后应能拿到");
    }
}
