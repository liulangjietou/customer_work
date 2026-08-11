package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 词表刷新的<b>自带线程驱动</b>：单线程守护定时器按固定间隔调用 {@link SensitiveWordRefresher#refreshOnce()}。
 *
 * <p><b>它解决什么</b>：{@link SensitiveWordRefresher#scheduledRefresh()} 上的 {@code @Scheduled} 要靠宿主开启
 * {@code @EnableScheduling} 才生效。宿主没开全局调度时（为一次词表刷新去开全局调度，会把容器里所有
 * {@code @Scheduled} Bean 一并激活，影响面远大于收益），就用本驱动替代——两条路径<b>二选一</b>，
 * 走本驱动时把 {@code SensitiveWordRefresher} 的 {@code enabled} 传 false，避免两边同时刷。</p>
 *
 * <p><b>本类不是 Bean</b>：starter 自动装配不注册它，由需要的宿主自行 new（并在关闭时调 {@link #stop()}；
 * 直接注册成 Bean 时 {@link DisposableBean} 会兜住容器关闭）。</p>
 *
 * <p><b>异常必须吞掉</b>：{@code scheduleWithFixedDelay} 的任务一旦抛出，后续所有轮次会被静默取消——
 * 那意味着词表从此再不更新且毫无迹象。所以每轮独立 try/catch，记 error 日志后等下一轮重试。</p>
 * @author owlzhangfq@gmail.com
 */
public class SensitiveWordRefreshDriver implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordRefreshDriver.class);

    /** 默认线程名；宿主可自定义，便于线程栈里一眼认出是哪个进程的刷新器。 */
    private static final String DEFAULT_THREAD_NAME = "sensitive-word-refresher";

    private final SensitiveWordRefresher refresher;
    private final ScheduledExecutorService scheduler;
    private final long intervalMs;

    public SensitiveWordRefreshDriver(SensitiveWordRefresher refresher, long intervalMs) {
        this(refresher, intervalMs, DEFAULT_THREAD_NAME);
    }

    /**
     * @param refresher  实际执行刷新的刷新器
     * @param intervalMs 轮询间隔（毫秒，必须为正）；间隔下限由调用方按自身配置语义决定，本类不做兜底修正
     * @param threadName 定时线程名，留空回落默认名
     */
    public SensitiveWordRefreshDriver(SensitiveWordRefresher refresher, long intervalMs, String threadName) {
        // fast-fail：参数错了要在启动期炸出来，不能等到守护线程里被 catch 吞掉后无声无息
        if (refresher == null) {
            throw new IllegalArgumentException("sensitive word refresher must not be null");
        }
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("sensitive word refresh interval must be positive: " + intervalMs);
        }
        String name = (threadName == null || threadName.isBlank()) ? DEFAULT_THREAD_NAME : threadName.trim();
        this.refresher = refresher;
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            // 守护线程：不阻塞 JVM 退出
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("sensitive word refresh driver started, thread={}, intervalMs={}", name, intervalMs);
    }

    /** 单次刷新；异常吞掉并记录，保证后续轮次继续。 */
    private void runOnce() {
        try {
            refresher.refreshOnce();
        } catch (Exception e) {
            log.error("sensitive word refresh round failed, code={}, intervalMs={}",
                "SENSITIVE-REFRESH-DRIVER-FAIL", intervalMs, e);
        }
    }

    /** 停止定时器；幂等，重复调用无副作用。 */
    public void stop() {
        scheduler.shutdownNow();
        log.info("sensitive word refresh driver stopped");
    }

    /** 定时器是否仍在运行（观测 / 单测）。 */
    public boolean isRunning() {
        return !scheduler.isShutdown();
    }

    @Override
    public void destroy() {
        stop();
    }
}
