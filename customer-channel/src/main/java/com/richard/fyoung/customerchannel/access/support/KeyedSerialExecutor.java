package com.richard.fyoung.customerchannel.access.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 key 串行、跨 key 并行的执行器。
 *
 * <p>用途：同一会话（同一 externalUserId）的消息必须串行处理（后一条等前一条回复完成），
 * 不同会话之间并行。实现为「每个 key 一条 {@link CompletableFuture} 任务链」，链尾完成后自动清理，
 * 底层复用一个共享的 cached 线程池（对话可能阻塞至数百秒，用 cached 避免固定池被长任务占满）。</p>
 * @author owlzhangfq@gmail.com
 */
public class KeyedSerialExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KeyedSerialExecutor.class);

    private final ExecutorService pool;
    private final ConcurrentMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public KeyedSerialExecutor(String threadNamePrefix) {
        this.pool = Executors.newCachedThreadPool(namedFactory(threadNamePrefix));
    }

    /**
     * 把任务追加到指定 key 的串行链尾。
     *
     * @param key  串行键
     * @param task 任务（内部已兜底，异常不会中断后续任务）
     */
    public void submit(String key, Runnable task) {
        tails.compute(key, (k, tail) -> {
            CompletableFuture<Void> base = (tail == null) ? CompletableFuture.completedFuture(null) : tail;
            CompletableFuture<Void> next = base.handleAsync((r, e) -> {
                runQuietly(task);
                return null;
            }, pool);
            // 链尾完成后清理（仅当仍是当前尾节点时移除，避免误删后续任务）
            next.whenComplete((r, e) -> tails.remove(key, next));
            return next;
        });
    }

    private void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("keyed serial task failed, code={}", "CHANNEL-ACCESS-SERIAL-TASK-FAIL", e);
        }
    }

    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
