package com.richard.fyoung.customerwork.infra.lock;

/**
 * 会话级串行锁 SPI：同一会话的请求串行执行，防止并发写 StateStore 把对话历史交叉覆盖。
 *
 * <p>进程内实现要求网关按会话做 sticky 路由才成立；一旦同一会话可能落到不同实例
 * （扩缩容、滚动发布、网关配置疏漏），就必须换分布式实现。</p>
 *
 * <p><b>释放必须与线程无关</b>：调用方在 Reactor 链里获取锁，而释放发生在 {@code doFinally}，
 * 两者不保证同一线程。因此本接口返回一个释放句柄，而不是依赖"谁加锁谁解锁"的线程绑定语义——
 * Redisson 的 {@code RLock} 正是绑定线程的，直接用会在跨线程释放时抛 IllegalMonitorStateException。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SessionLock {

    /**
     * 获取会话锁，阻塞直到成功或超时。
     *
     * <p>调用方负责在弹性线程池（boundedElastic）上调用，不要阻塞 Netty 事件循环。</p>
     *
     * @return 释放句柄；获取失败时抛 {@link SessionLockTimeoutException}
     */
    Releasable acquire(String sessionId);

    /** 释放句柄：持有释放所需的全部信息（如 Redisson 的 permitId），与获取时的线程无关。 */
    interface Releasable {
        void release();
    }
}
