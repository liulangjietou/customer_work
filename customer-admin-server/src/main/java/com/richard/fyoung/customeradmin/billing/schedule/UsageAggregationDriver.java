package com.richard.fyoung.customeradmin.billing.schedule;

import com.richard.fyoung.customeradmin.billing.service.UsageAggregationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 用量归集的周期驱动（自带守护线程）。
 *
 * <p><b>为什么不用 {@code @Scheduled}</b>：admin 至今没开 {@code @EnableScheduling}，
 * 为一个归集任务打开全局调度会把容器里所有带 {@code @Scheduled} 的 Bean 一并激活，
 * 影响面远大于收益——这个判断在 {@code SensitiveWordRefreshTask} 上已经下过一次，这里沿用。</p>
 *
 * <p><b>为什么是固定间隔而不是 cron</b>：归集幂等（同一天重跑就是覆盖），多跑几次只是多扫一遍日志，
 * 不会算错数。用固定间隔就不必引入 cron 解析，也天然容忍实例重启——重启后下一轮照跑，
 * 不会像"每天 02:00"那样错过就得等一整天。</p>
 * @author owlzhangfq@gmail.com
 */
public class UsageAggregationDriver {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregationDriver.class);

    private static final String THREAD_NAME = "admin-usage-aggregation";

    /** 间隔下限：配置填得过小会把原始日志表反复全扫。 */
    private static final long MIN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);

    private final UsageAggregationService aggregationService;
    private final long intervalMs;
    private final int backfillDays;
    private final Thread worker;
    private volatile boolean running = true;

    public UsageAggregationDriver(UsageAggregationService aggregationService, long intervalMs, int backfillDays) {
        this.aggregationService = aggregationService;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        this.backfillDays = Math.max(1, backfillDays);
        this.worker = new Thread(this::loop, THREAD_NAME);
        this.worker.setDaemon(true);
        this.worker.start();
        log.info("usage aggregation driver started, intervalMs={}, backfillDays={}",
            this.intervalMs, this.backfillDays);
    }

    private void loop() {
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            runOnce();
        }
    }

    /** 归集 T-1 起的若干天：回溯是为了兜住任务失败或实例停机造成的空洞。 */
    void runOnce() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (int i = 0; i < backfillDays; i++) {
            LocalDate target = yesterday.minusDays(i);
            try {
                aggregationService.aggregate(target);
            } catch (Exception e) {
                // 单日失败不阻断其余日期：一天算不出来不该连带让整周的账都补不上
                log.error("usage aggregation failed, code={}, date={}", "BILLING-AGGREGATE-FAIL", target, e);
            }
        }
    }

    public void stop() {
        running = false;
        worker.interrupt();
    }
}
