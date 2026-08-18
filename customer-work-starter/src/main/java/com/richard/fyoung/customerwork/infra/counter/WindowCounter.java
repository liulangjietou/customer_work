package com.richard.fyoung.customerwork.infra.counter;

/**
 * 时间窗口计数器 SPI：接入层限流与模型成本熔断共用。
 *
 * <p>两者本来就是同一件事——在一个时间窗内累加、判断是否越过阈值。此前各自实现了一套进程内计数，
 * 多实例部署时配额被放大 N 倍（N 个实例各算各的）。抽成 SPI 后换一个实现就同时解决两处。</p>
 *
 * <p>实现须保证：窗口滚动由实现内部负责（调用方只给窗口长度，不管窗口边界），
 * 且计数键在窗口结束后能自行回收，不留无界增长。</p>
 * @author owlzhangfq@gmail.com
 */
public interface WindowCounter {

    /**
     * 固定窗口累加并返回累加后的值。
     *
     * @param key           计数键（调用方保证语义唯一，如 {@code ratelimit:key:xxx}）
     * @param delta         本次增量
     * @param windowSeconds 窗口长度（秒）
     * @return 当前窗口累加后的总量
     */
    long increment(String key, long delta, int windowSeconds);

    /**
     * 回退固定窗口的计数（越过阈值后把本次增量退回去，避免被拒的请求仍占额度）。
     *
     * <p>不保证与 {@link #increment} 严格原子成对：并发下允许短暂的计数虚高，
     * 这对"防成本失控"的目的是安全方向的偏差。</p>
     */
    void decrement(String key, long delta, int windowSeconds);

    /** 只读当前窗口累计值，不产生任何计数副作用。 */
    long current(String key, int windowSeconds);

    /**
     * 滑动窗口取用一次配额。
     *
     * <p>与固定窗口分开是因为语义不同：滑动窗口要记录每次请求的时刻，而不是往一个数上加。
     * 超限时不得记录本次请求，否则持续打压会让窗口永远不恢复。</p>
     *
     * @return true=放行（已记录本次），false=超限（未记录）
     */
    boolean tryAcquireSliding(String key, int limit, int windowSeconds);

    /**
     * 滑动窗口累加"量"并返回窗口内累计量。
     *
     * <p>与 {@link #tryAcquireSliding} 的区别是"量"与"次"：那个方法一次只能取 1 个额度，
     * 而 token 消耗是一次几百上千的变量。逐条记时间戳对 token 不可行（高 QPS 下内存/Redis 直接爆），
     * 故实现采用<b>分桶近似</b>：窗口切成固定份数的小桶，只按桶累加、按桶过期。</p>
     *
     * <p>实现须保证统计范围<b>不短于</b> {@code windowSeconds}（多留一个桶即可）：宁可把
     * 刚出窗的一小段仍算进来（限得偏严），也不能提前把窗内用量丢掉（限得偏松）——
     * 配额的误差方向必须是 fail-closed。</p>
     *
     * @param key           计数键
     * @param delta         本次增量（如实际消耗的 token 数）
     * @param windowSeconds 滑动窗口长度（秒）
     * @return 窗口内累计量（含本次）
     */
    long incrementSlidingSum(String key, long delta, int windowSeconds);

    /** 只读滑动窗口累计量，不产生任何计数副作用（含清理）。 */
    long currentSlidingSum(String key, int windowSeconds);
}
