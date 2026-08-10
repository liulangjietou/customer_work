package com.richard.fyoung.customeradmin.contentguard.runtime;

import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordRefreshDriver;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordRefresher;
import jakarta.annotation.PreDestroy;

/**
 * admin 侧的词表刷新调度：委托 starter 的 {@link SensitiveWordRefreshDriver} 周期性调用
 * {@link SensitiveWordRefresher#refreshOnce()}。
 *
 * <p><b>为什么不用 {@code @Scheduled}</b>：starter 的 {@code SensitiveWordRefresher} 上确实标了
 * {@code @Scheduled}，但那要靠 {@code @EnableScheduling} 才生效，而 admin 至今没开启全局调度。
 * 为一个词表刷新去打开全局调度，会把容器里所有带 {@code @Scheduled} 的 Bean 一并激活——影响面
 * 远大于收益。改用自带守护线程的驱动，职责闭合。</p>
 *
 * <p>本类只保留 admin 特有的两件事：轮询间隔下限兜底（配置值可能填 0 甚至负数）与线程命名。</p>
 * @author owlzhangfq@gmail.com
 */
public class SensitiveWordRefreshTask {

    /** 轮询间隔下限：配置填得过小会把客服端库压出无谓的指纹查询。 */
    private static final long MIN_INTERVAL_MS = 1000L;

    /** 定时线程名，线程栈里一眼可辨。 */
    private static final String THREAD_NAME = "admin-sensitive-word-refresher";

    private final SensitiveWordRefreshDriver driver;

    public SensitiveWordRefreshTask(SensitiveWordRefresher refresher, long intervalMs) {
        this.driver = new SensitiveWordRefreshDriver(refresher, Math.max(MIN_INTERVAL_MS, intervalMs), THREAD_NAME);
    }

    @PreDestroy
    public void stop() {
        driver.stop();
    }
}
