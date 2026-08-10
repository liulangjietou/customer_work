package com.richard.fyoung.customerwork.lock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 进程内会话串行锁（默认实现，等价于改造前 {@code CustomerServiceService} 内嵌的 Semaphore）。
 *
 * <p>相比原实现补了一件事：<b>锁对象按等待者计数回收</b>。原来每见一个新 sessionId 就往
 * Map 里放一个 Semaphore 且永不移除，长期运行会把会话数累积成内存泄漏。这里在最后一个
 * 使用者释放时把条目摘掉，用 compute 保证"计数归零"与"移除"是同一次原子操作，
 * 不会与正在进入的请求擦身而过。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySessionLock implements SessionLock {

    private final Map<String, Entry> locks = new ConcurrentHashMap<>();
    private final long waitSeconds;

    public InMemorySessionLock(long waitSeconds) {
        this.waitSeconds = waitSeconds <= 0 ? Long.MAX_VALUE : waitSeconds;
    }

    @Override
    public Releasable acquire(String sessionId) {
        Entry entry = locks.compute(sessionId, (k, existing) -> {
            Entry e = existing == null ? new Entry() : existing;
            e.users++;
            return e;
        });
        try {
            if (waitSeconds == Long.MAX_VALUE) {
                entry.semaphore.acquire();
            } else if (!entry.semaphore.tryAcquire(waitSeconds, TimeUnit.SECONDS)) {
                releaseEntry(sessionId);
                throw new SessionLockTimeoutException(sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseEntry(sessionId);
            throw new SessionLockTimeoutException(sessionId);
        }
        return () -> {
            entry.semaphore.release();
            releaseEntry(sessionId);
        };
    }

    /** 使用者计数减一，归零即摘除条目（与 acquire 的 compute 互斥，不会漏删或误删）。 */
    private void releaseEntry(String sessionId) {
        locks.compute(sessionId, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            existing.users--;
            return existing.users <= 0 ? null : existing;
        });
    }

    /** 当前跟踪的会话数（测试用，用于验证锁对象确实被回收）。 */
    int trackedSessions() {
        return locks.size();
    }

    private static final class Entry {
        private final Semaphore semaphore = new Semaphore(1);
        private int users;
    }
}
