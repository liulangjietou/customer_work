package com.richard.fyoung.customerwork.infra.counter;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 Redis 的跨实例窗口计数（多副本部署时的正确实现）。
 *
 * <p><b>计数用 Lua 脚本原子执行</b>：分布式限流最怕"查完再写"的竞态——N 个实例同时读到未超限、
 * 各自放行一个请求，配额照样被击穿。把判断与写入放进一次 Redis 调用才有意义。</p>
 *
 * <p><b>Redis 不可达时降级到进程内计数，而不是直接放行</b>：限流与成本熔断都是保护性能力，
 * 基础设施故障时全放行等于保护完全消失；退回单机配额虽然总量会放大成 N 倍，
 * 但仍拦得住单实例上的失控流量。降级只打一次 error 日志，不逐请求刷屏。</p>
 * @author owlzhangfq@gmail.com
 */
public class RedissonWindowCounter implements WindowCounter {

    private static final Logger log = LoggerFactory.getLogger(RedissonWindowCounter.class);

    /**
     * 固定窗口累加：首次写入时才设过期，避免每次请求都重置 TTL 把窗口无限延长。
     * 返回累加后的值。
     */
    private static final String FIXED_WINDOW_SCRIPT =
        "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
            + "if v == tonumber(ARGV[1]) then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end "
            + "return v";

    /**
     * 滑动窗口取用：先清窗口外的旧记录，再判是否已达上限，未达才写入本次时刻。
     * 超限时刻意不写入——持续打压若仍记录，窗口会永远不恢复。
     */
    private static final String SLIDING_WINDOW_SCRIPT =
        "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) "
            + "local count = redis.call('ZCARD', KEYS[1]) "
            + "if count >= tonumber(ARGV[2]) then return 0 end "
            + "redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4]) "
            + "redis.call('PEXPIRE', KEYS[1], ARGV[5]) "
            + "return 1";

    private final RedissonClient redisson;
    private final String keyPrefix;

    /** Redis 不可达时的降级去处；同时也是"多实例配额被放大"这一已知代价的承担者。 */
    private final WindowCounter fallback;

    /** 降级日志只打一次：Redis 抖动时逐请求打 error 会把日志刷爆，反而淹没真正的问题。 */
    private volatile boolean degradedLogged = false;

    public RedissonWindowCounter(RedissonClient redisson, String keyPrefix, WindowCounter fallback) {
        this.redisson = redisson;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "cw:counter:" : keyPrefix;
        this.fallback = fallback == null ? new InMemoryWindowCounter() : fallback;
    }

    @Override
    public long increment(String key, long delta, int windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        String redisKey = fixedWindowKey(key, windowMs);
        try {
            Long value = eval(FIXED_WINDOW_SCRIPT, RScript.ReturnType.INTEGER, List.of(redisKey),
                String.valueOf(delta), String.valueOf(windowMs * 2));
            return value == null ? delta : value;
        } catch (Exception e) {
            logDegraded(e);
            return fallback.increment(key, delta, windowSeconds);
        }
    }

    @Override
    public void decrement(String key, long delta, int windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        try {
            redisson.getAtomicLong(fixedWindowKey(key, windowMs)).addAndGet(-delta);
        } catch (Exception e) {
            logDegraded(e);
            fallback.decrement(key, delta, windowSeconds);
        }
    }

    @Override
    public long current(String key, int windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        try {
            return redisson.getAtomicLong(fixedWindowKey(key, windowMs)).get();
        } catch (Exception e) {
            logDegraded(e);
            return fallback.current(key, windowSeconds);
        }
    }

    @Override
    public boolean tryAcquireSliding(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        // member 必须唯一，否则同一毫秒内的两个请求会被 ZADD 当成同一个成员覆盖掉一个
        String member = now + "-" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        try {
            Long allowed = eval(SLIDING_WINDOW_SCRIPT, RScript.ReturnType.INTEGER, List.of(keyPrefix + key),
                String.valueOf(now - windowMs), String.valueOf(limit),
                String.valueOf(now), member, String.valueOf(windowMs * 2));
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            logDegraded(e);
            return fallback.tryAcquireSliding(key, limit, windowSeconds);
        }
    }

    /** 固定窗口的键带窗口序号：窗口滚动即换键，天然避免跨窗口累加，过期由 TTL 兜底回收。 */
    private String fixedWindowKey(String key, long windowMs) {
        return keyPrefix + key + ":" + (System.currentTimeMillis() / windowMs);
    }

    private Long eval(String script, RScript.ReturnType returnType, List<Object> keys, String... args) {
        return redisson.getScript(StringCodec.INSTANCE)
            .eval(RScript.Mode.READ_WRITE, script, returnType, keys, (Object[]) args);
    }

    private void logDegraded(Exception e) {
        if (degradedLogged) {
            return;
        }
        degradedLogged = true;
        log.error("distributed window counter unavailable, degraded to in-process counting, code={}",
            "COUNTER-REDIS-DEGRADED", e);
    }
}
