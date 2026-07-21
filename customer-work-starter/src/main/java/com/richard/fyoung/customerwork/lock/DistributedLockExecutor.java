package com.richard.fyoung.customerwork.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分布式锁执行器：同一个 {@code lockKey} 在跨进程/跨实例范围内互斥执行 {@code action}。
 *
 * <p>用于保护"多个调用方可能并发操作同一资源、需要串行化"的业务场景（如后台管理并发写操作），
 * 不是通用限流/幂等组件——action 内部逻辑仍需自行保证幂等或事务完整性，本组件只保证同一时刻
 * 至多一个调用方持有锁。</p>
 * @author owlzhangfq@gmail.com
 */
public interface DistributedLockExecutor {

    /**
     * 尝试加锁并执行 {@code action}，返回其结果。
     *
     * @param lockKey   锁资源标识，调用方自行规划命名空间前缀，避免跨业务碰撞
     * @param waitTime  等待获取锁的最长时间；{@link Duration#ZERO} 表示不等待，立即失败（fast fail）
     * @param leaseTime 持锁最长时间，超时自动释放（防止持锁方异常退出后锁永久不释放）；
     *                  必须覆盖 {@code action} 的预期最长执行时间
     * @throws LockAcquireTimeoutException 在 {@code waitTime} 内未能获取到锁
     */
    <T> T execute(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action);

    /** 无返回值版本，见 {@link #execute(String, Duration, Duration, Supplier)}。 */
    default void execute(String lockKey, Duration waitTime, Duration leaseTime, Runnable action) {
        execute(lockKey, waitTime, leaseTime, () -> {
            action.run();
            return null;
        });
    }
}
