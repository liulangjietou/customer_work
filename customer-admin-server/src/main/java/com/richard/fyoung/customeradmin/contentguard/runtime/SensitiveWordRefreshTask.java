package com.richard.fyoung.customeradmin.contentguard.runtime;

import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordRefresher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * admin 侧的词表刷新调度：自带一个单线程定时器周期性调用 {@link SensitiveWordRefresher#refreshOnce()}。
 *
 * <p><b>为什么不用 {@code @Scheduled}</b>：starter 的 {@code SensitiveWordRefresher} 上确实标了
 * {@code @Scheduled}，但那要靠 {@code @EnableScheduling} 才生效，而 admin 至今没开启全局调度。
 * 为一个词表刷新去打开全局调度，会把容器里所有带 {@code @Scheduled} 的 Bean 一并激活——影响面
 * 远大于收益。自带一个守护线程定时器，职责闭合在本模块内。</p>
 * @author owlzhangfq@gmail.com
 */
public class SensitiveWordRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordRefreshTask.class);

    private final SensitiveWordRefresher refresher;
    private final ScheduledExecutorService scheduler;

    public SensitiveWordRefreshTask(SensitiveWordRefresher refresher, long intervalMs) {
        this.refresher = refresher;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "admin-sensitive-word-refresher");
            t.setDaemon(true);
            return t;
        });
        long delay = Math.max(1000L, intervalMs);
        scheduler.scheduleWithFixedDelay(this::runOnce, delay, delay, TimeUnit.MILLISECONDS);
        log.info("admin sensitive word refresher started, intervalMs={}", delay);
    }

    /** 单次刷新；异常必须吞掉——定时任务里抛出去会让 scheduleWithFixedDelay 静默停掉后续所有执行。 */
    private void runOnce() {
        try {
            refresher.refreshOnce();
        } catch (Exception e) {
            log.error("admin sensitive word refresh failed, code={}", "CONTENTGUARD-REFRESH-FAIL", e);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        log.info("admin sensitive word refresher stopped");
    }
}
