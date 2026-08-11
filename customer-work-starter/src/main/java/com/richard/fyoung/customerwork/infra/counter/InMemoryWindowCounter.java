package com.richard.fyoung.customerwork.infra.counter;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内窗口计数（默认实现）。
 *
 * <p>单实例部署下这就是正确答案；多实例部署下每个实例各算各的，等于把配额放大成 N 倍，
 * 此时应换 {@link RedissonWindowCounter}。行为与改造前 {@code RateLimitWebFilter} 内嵌的
 * 计数逻辑逐字等价——包括窗口自带到期时刻、按到期时刻清扫（不能按"是否等于当前窗口序号"判断，
 * 否则窗口大小不同的规则会互相误删对方的有效窗口）。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryWindowCounter implements WindowCounter {

    /** 跟踪的计数键上限，超过则清扫过期窗口，避免 Map 无界增长导致内存泄漏。 */
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final Map<String, Object> windows = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, long delta, int windowSeconds) {
        FixedWindow window = fixedWindow(key, windowSeconds);
        if (windows.size() > MAX_TRACKED_KEYS) {
            evictExpiredFixedWindows(System.currentTimeMillis());
        }
        return window.count.addAndGet(delta);
    }

    @Override
    public void decrement(String key, long delta, int windowSeconds) {
        Object existing = windows.get(key);
        if (existing instanceof FixedWindow fw) {
            fw.count.addAndGet(-delta);
        }
    }

    @Override
    public long current(String key, int windowSeconds) {
        Object existing = windows.get(key);
        if (!(existing instanceof FixedWindow fw)) {
            return 0L;
        }
        // 窗口已过期即视为 0：只读路径不负责滚动窗口，否则读操作会产生写副作用
        return fw.expireAtMs <= System.currentTimeMillis() ? 0L : fw.count.get();
    }

    @Override
    public boolean tryAcquireSliding(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        long windowStart = now - windowMs;
        SlidingWindow sw = (SlidingWindow) windows.compute(key, (k, existing) -> {
            SlidingWindow w = existing instanceof SlidingWindow s ? s : new SlidingWindow(windowMs);
            synchronized (w.timestamps) {
                Iterator<Long> it = w.timestamps.iterator();
                while (it.hasNext() && it.next() < windowStart) {
                    it.remove();
                }
            }
            return w;
        });
        if (windows.size() > MAX_TRACKED_KEYS) {
            evictEmptySlidingWindows(now);
        }
        synchronized (sw.timestamps) {
            if (sw.timestamps.size() >= limit) {
                return false;
            }
            sw.timestamps.addLast(now);
            return true;
        }
    }

    private FixedWindow fixedWindow(String key, int windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        long currentWindow = System.currentTimeMillis() / windowMs;
        return (FixedWindow) windows.compute(key, (k, w) -> {
            if (!(w instanceof FixedWindow fw) || fw.window != currentWindow) {
                return new FixedWindow(currentWindow, (currentWindow + 1) * windowMs);
            }
            return w;
        });
    }

    private void evictExpiredFixedWindows(long now) {
        windows.entrySet().removeIf(e ->
            e.getValue() instanceof FixedWindow fw && fw.expireAtMs <= now);
    }

    private void evictEmptySlidingWindows(long now) {
        windows.entrySet().removeIf(e -> {
            if (!(e.getValue() instanceof SlidingWindow sw)) {
                return false;
            }
            synchronized (sw.timestamps) {
                return sw.timestamps.isEmpty() || sw.timestamps.peekFirst() < now - sw.windowMs;
            }
        });
    }

    /** 固定时间窗内的计数（自带到期时刻，供跨窗口大小安全清扫）。 */
    private static final class FixedWindow {
        private final long window;
        private final long expireAtMs;
        private final AtomicLong count = new AtomicLong();

        FixedWindow(long window, long expireAtMs) {
            this.window = window;
            this.expireAtMs = expireAtMs;
        }
    }

    /** 滑动时间窗：记录每个请求的时间戳，过期出队（自带窗口大小，供跨规则安全清扫）。 */
    private static final class SlidingWindow {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        private final long windowMs;

        SlidingWindow(long windowMs) {
            this.windowMs = windowMs;
        }
    }
}
