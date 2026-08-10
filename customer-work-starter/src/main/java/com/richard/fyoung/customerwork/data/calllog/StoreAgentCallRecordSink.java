package com.richard.fyoung.customerwork.data.calllog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认调用记录 Sink：异步落 {@link AgentCallLogStore}，绝不阻塞响应事件流。
 *
 * <p>用一个小容量有界线程池承接落库任务（单线程足以顺序落库，峰值排队 2048 条）：{@link #emit} 只做
 * 提交、立即返回，落库在后台线程执行，采集/落库异常在任务内自我消化。队列打满时按 fast-fail 丢弃并记
 * error（宁可丢采集数据也不拖垮主链路，符合"采集不打断主链路"约束）。容器关闭时优雅停池。</p>
 * @author owlzhangfq@gmail.com
 */
public class StoreAgentCallRecordSink implements AgentCallRecordSink, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StoreAgentCallRecordSink.class);

    private static final int QUEUE_CAPACITY = 2048;
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private final AgentCallLogStore store;
    private final ThreadPoolExecutor executor;

    public StoreAgentCallRecordSink(AgentCallLogStore store) {
        this.store = store;
        AtomicLong threadIndex = new AtomicLong(0);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "agent-call-log-sink-" + threadIndex.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    }

    @Override
    public void emit(AgentCallRecord record) {
        if (record == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    store.save(record);
                } catch (Exception e) {
                    log.error("agent call log async save failed, code={}, requestId={}",
                        "CALLLOG-ASYNC-SAVE-FAIL", record.requestId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列打满：丢弃本条采集，绝不反压主链路
            log.error("agent call log sink saturated, drop record, code={}, requestId={}",
                "CALLLOG-SINK-SATURATED", record.requestId());
        }
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
        log.info("agent call log sink stopped");
    }
}
