package com.richard.fyoung.customerwork.infra.lock;

import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 跨实例的会话串行锁（多副本部署用）。
 *
 * <p><b>为什么用 {@code RPermitExpirableSemaphore} 而不是 {@code RLock}</b>：Redisson 的 RLock 是
 * 线程绑定的可重入锁，解锁时校验持有线程。而本项目在 Reactor 链里加锁、在 {@code doFinally} 里释放，
 * 两者不保证同一线程，用 RLock 会在释放时抛 IllegalMonitorStateException。
 * PermitExpirableSemaphore 的 acquire 返回一个 permitId，释放只认这个 id 不认线程，正好匹配。</p>
 *
 * <p>lease 时间是硬保险：实例崩溃时锁不会永久留在 Redis 里把整个会话卡死。
 * 它必须显著大于单次对话的最长处理时间，否则长响应会在处理中途失去互斥保护。</p>
 * @author owlzhangfq@gmail.com
 */
public class RedissonSessionLock implements SessionLock {

    private static final Logger log = LoggerFactory.getLogger(RedissonSessionLock.class);

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final long waitSeconds;
    private final long leaseSeconds;

    /** Redis 不可达时的降级去处：退回单实例串行，总比完全没有互斥好。 */
    private final SessionLock fallback;

    /** 是否允许 Redis 故障时退化为进程内锁；多副本强一致入口必须关闭。 */
    private final boolean fallbackOnError;

    private volatile boolean degradedLogged = false;

    public RedissonSessionLock(RedissonClient redisson, String keyPrefix,
                               long waitSeconds, long leaseSeconds, SessionLock fallback) {
        this(redisson, keyPrefix, waitSeconds, leaseSeconds, fallback, true);
    }

    public RedissonSessionLock(RedissonClient redisson, String keyPrefix,
                               long waitSeconds, long leaseSeconds, SessionLock fallback,
                               boolean fallbackOnError) {
        this.redisson = redisson;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "cw:sessionlock:" : keyPrefix;
        this.waitSeconds = waitSeconds <= 0 ? 10 : waitSeconds;
        this.leaseSeconds = leaseSeconds <= 0 ? 120 : leaseSeconds;
        this.fallback = fallback == null ? new InMemorySessionLock(waitSeconds) : fallback;
        this.fallbackOnError = fallbackOnError;
    }

    @Override
    public Releasable acquire(String sessionId) {
        RPermitExpirableSemaphore semaphore;
        String permitId;
        try {
            semaphore = redisson.getPermitExpirableSemaphore(keyPrefix + sessionId);
            // 幂等：已存在则不改动。permits=1 即互斥
            semaphore.trySetPermits(1);
            permitId = semaphore.tryAcquire(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionLockTimeoutException(sessionId);
        } catch (Exception e) {
            if (!fallbackOnError) {
                throw new SessionLockUnavailableException(sessionId, e);
            }
            logDegraded(e);
            return fallback.acquire(sessionId);
        }
        if (permitId == null) {
            throw new SessionLockTimeoutException(sessionId);
        }
        return () -> releaseQuietly(semaphore, permitId, sessionId);
    }

    /**
     * 释放失败不上抛：这里跑在 {@code doFinally} 里，抛出去只会掩盖真正的业务异常。
     * 而且 lease 到期后锁会自动释放，最坏情况是多占用一小段时间，不会永久卡死。
     */
    private void releaseQuietly(RPermitExpirableSemaphore semaphore, String permitId, String sessionId) {
        try {
            semaphore.release(permitId);
        } catch (Exception e) {
            log.error("release session lock failed, code={}, sessionId={}", "SESSION-LOCK-RELEASE-FAIL",
                sessionId, e);
        }
    }

    private void logDegraded(Exception e) {
        if (degradedLogged) {
            return;
        }
        degradedLogged = true;
        log.error("distributed session lock unavailable, degraded to in-process lock, code={}",
            "SESSION-LOCK-REDIS-DEGRADED", e);
    }
}
