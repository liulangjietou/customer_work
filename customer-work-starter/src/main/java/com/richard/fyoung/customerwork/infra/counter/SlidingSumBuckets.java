package com.richard.fyoung.customerwork.infra.counter;

/**
 * 滑动求和的分桶口径（进程内实现与 Redis 实现共用）。
 *
 * <p>抽出来是因为两个实现必须算出<b>同一个桶索引</b>：Redis 不可达时会降级到进程内计数，
 * 两边口径不一致会让降级前后的用量无法相互解释。</p>
 *
 * <p>包级可见：这是实现细节，不属于 {@link WindowCounter} 的 SPI 表面。</p>
 * @author owlzhangfq@gmail.com
 */
final class SlidingSumBuckets {

    /**
     * 分桶数：窗口切成这么多份，按桶累加、按桶过期。
     *
     * <p>30 桶意味着最坏误差是一个桶的时长（30 分钟窗口即 1 分钟，约 3%），
     * 换来的是"每个计数键只占常数级空间"——逐条记 token 时间戳在高 QPS 下会直接把内存/Redis 吃光。</p>
     */
    static final int BUCKETS = 30;

    private SlidingSumBuckets() {
    }

    /** 桶时长：窗口均分成 {@link #BUCKETS} 份，最细到秒（再细只会增加桶数，不增加精度价值）。 */
    static long bucketMs(int windowSeconds) {
        return Math.max(1000L, windowSeconds * 1000L / BUCKETS);
    }

    /** 当前时刻所属的桶索引。 */
    static long currentBucket(long bucketMs) {
        return System.currentTimeMillis() / bucketMs;
    }

    /**
     * 最老的有效桶索引（小于它的桶已出窗）。
     *
     * <p>覆盖窗口所需桶数向上取整后，在当前桶之外整整保留这么多个——统计范围因此落在
     * {@code [window, window + bucket)}，偏差方向是"多算一点"，即限得偏严。配额的误差
     * 必须朝 fail-closed 偏：少算会让超额的请求溜过去，多算只是让人早几十秒被拦。</p>
     */
    static long oldestBucket(long currentBucket, int windowSeconds, long bucketMs) {
        long span = (windowSeconds * 1000L + bucketMs - 1) / bucketMs;
        return currentBucket - span;
    }

    /** 计数键的存活时长（毫秒）：略大于一个窗口，让不再活跃的键自行回收。 */
    static long retentionMs(int windowSeconds) {
        return windowSeconds * 2000L;
    }
}
