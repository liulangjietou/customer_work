package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务调度模式全局配置。
 *
 * <p>{@link #mode} 决定周期调度由谁驱动，两种模式互斥执行以避免"双跑"：
 * <ul>
 *   <li>{@code internal}（默认）：admin-server 内置动态调度器（{@code ScheduledTaskScheduler}）
 *       按任务 cron 执行，无需外部 XXL-JOB；此模式下 XXL-JOB 通用 JobHandler 会主动跳过执行；</li>
 *   <li>{@code xxl-job}：周期以 XXL-JOB 控制台配置为准，内置调度器不注册任何任务，cron 字段仅保存展示。</li>
 * </ul>
 * 手动触发路径（{@code /trigger} 接口）与本配置无关，任何模式下都可用。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.scheduler")
public class AdminSchedulerProperties {

    public static final String MODE_INTERNAL = "internal";
    public static final String MODE_XXL_JOB = "xxl-job";

    /** 调度模式：internal（内置，默认）｜xxl-job（外部）。 */
    private String mode = MODE_INTERNAL;

    /** 内置调度器线程池大小：需 > 1 以支持多个任务并发执行（单次执行可能是分钟级同步调用）。 */
    private int poolSize = 4;

    /** 是否内置调度模式（非 xxl-job 一律视为 internal，配置写错也走安全默认）。 */
    public boolean isInternalMode() {
        return !MODE_XXL_JOB.equalsIgnoreCase(mode);
    }

    /** 是否外部 XXL-JOB 调度模式。 */
    public boolean isXxlJobMode() {
        return MODE_XXL_JOB.equalsIgnoreCase(mode);
    }
}
