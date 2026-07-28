package com.richard.fyoung.customerwork.sensitiveword;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 默认命中投递实现：单线程有界队列异步落 {@link SensitiveWordHitLogStore}（照 {@code StoreAgentCallRecordSink}）。
 *
 * <p>{@link #emit} 只做提交、立即返回；落库在后台守护线程执行、异常自我消化。队列打满时<b>丢弃并计数</b>，
 * 绝不反压——命中日志是旁路观测，宁可丢几条记录，也不能让落库把对话链路拖住。容器关闭时优雅停池，
 * 给在途记录 5 秒落库窗口。</p>
 * @author owlzhangfq@gmail.com
 */
public class AsyncSensitiveWordHitSink implements SensitiveWordHitSink, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AsyncSensitiveWordHitSink.class);

    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private final SensitiveWordHitLogStore store;
    private final ThreadPoolExecutor executor;

    /** 因队列打满被丢弃的记录数（观测 / 单测）。 */
    private final LongAdder droppedCount = new LongAdder();

    public AsyncSensitiveWordHitSink(SensitiveWordHitLogStore store, int queueCapacity) {
        this.store = store;
        AtomicLong threadIndex = new AtomicLong(0);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
            r -> {
                Thread t = new Thread(r, "sensitive-hit-log-sink-" + threadIndex.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    }

    @Override
    public void emit(SensitiveWordHitRecord record) {
        if (record == null) {
            return;
        }
        try {
            executor.execute(() -> store.save(record));
        } catch (RejectedExecutionException e) {
            droppedCount.increment();
            log.error("sensitive hit log sink saturated, drop record, code={}, direction={}",
                "SENSITIVE-HITLOG-SATURATED", record.direction());
        }
    }

    /** 被丢弃的记录数（观测 / 单测）。 */
    public long droppedCount() {
        return droppedCount.sum();
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("sensitive hit log sink stopped, dropped={}", droppedCount.sum());
    }
}
