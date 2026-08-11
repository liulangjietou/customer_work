package com.richard.fyoung.customerwork.infra.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link DistributedLockExecutor} 的 Redisson 实现。
 *
 * <p>解锁前用 {@link RLock#isHeldByCurrentThread()} 判断，避免 {@code leaseTime} 到期后锁已被
 * Redisson 自动释放（甚至被其它线程重新持有）时，本线程再调用 {@code unlock} 抛
 * {@code IllegalMonitorStateException} 或误删别人的锁——Redisson 内部已用线程标识做 CAS 校验，
 * 这里只是提前短路，避免无意义的一次远程调用。</p>
 *
 * <p>加锁失败只归一为 {@link LockAcquireTimeoutException} 一种情况（超时未获取到锁，含被中断）；
 * Redis 连接不可达等基础设施异常不在此吞掉，直接向上抛出——分布式锁场景下"连不上 Redis"和
 * "锁被别人持有"是完全不同性质的失败，不能混为一谈退化成同一个"稍后重试"提示。</p>
 * @author owlzhangfq@gmail.com
 */
public class RedissonDistributedLockExecutor implements DistributedLockExecutor {

    private final RedissonClient redissonClient;

    public RedissonDistributedLockExecutor(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T execute(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquireTimeoutException(lockKey);
        }
        if (!acquired) {
            throw new LockAcquireTimeoutException(lockKey);
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
