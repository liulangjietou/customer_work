package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 死信队列配置。
 *
 * <p>死信的全部意义是"进程/实例挂了之后这笔还能补回来"，memory 模式恰恰在最需要它的那次故障中
 * 一起没了，生产必须切 jdbc。</p>
 */
@Data
public class DeadLetterProperties {

    /** 总开关（默认开）：关掉后 {@code record} 直接返回空，等同于回到"只记 error"。 */
    private boolean enabled = true;

    /** 存储模式：memory（进程内，默认，生产不可用）| jdbc（落 cw_dead_letter）。 */
    private String storeMode = "memory";

    /** 重投上限；耗尽转已放弃（不静默丢弃，留给人工捞）。 */
    private int maxAttempts = 5;

    /**
     * 基础退避毫秒，实际间隔为 {@code base * 2^attempts}。
     *
     * <p>默认 30s：第 1 次退 30s、第 5 次约 8 分钟。下游多半是被打挂了或正在重启，
     * 固定短间隔的密集重试只会把它按在地上。</p>
     */
    private long baseBackoffMs = 30_000L;

    /** 单轮重投的最大条数：避免积压时一轮跑太久，宁可多跑几轮。 */
    private int batchSize = 50;

    /** 单条重投租约时长；处理实例崩溃后到期可被其他副本接管。 */
    private long leaseMs = 60_000L;

    /** 重投巡检间隔（毫秒）。 */
    private long scanIntervalMs = 60_000L;

    /** 健康检查进入 DEGRADED 的待重投积压阈值。 */
    private long degradedPendingThreshold = 100L;
}
