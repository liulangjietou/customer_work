package com.richard.fyoung.customerwork.core.runtime;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.lock.DistributedLockExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 定时任务的多副本互斥。
 *
 * <p><b>要解决的问题</b>：队列型调度（Outbox、死信重试）靠表里的 {@code lease_owner} 抢占，
 * 多副本天然安全；而<b>状态机型</b>调度器——审批超时、转人工 SLA、工单 SLA——是
 * 「扫一批处于某状态的行，逐条改状态并触发动作」，没有任何抢占。多副本部署时每个副本
 * 都会扫到同一批行：超时动作执行两次、SLA 告警重复发。</p>
 *
 * <p><b>抢不到就跳过这一轮，不排队等待</b>：定时任务下一轮还会来，排队等只会让副本堆在锁上，
 * 甚至在任务比周期长时越积越多。因此 waitTime 取零。</p>
 *
 * <p><b>默认不加锁</b>：单副本部署不需要它，而无条件加锁会让所有部署都依赖 Redis 可用性——
 * 定时任务是旁路，不该因为锁服务抖动就整体停摆。多副本部署显式开
 * {@code customer-work.distributed.scheduler-lease-enabled=true}。</p>
 *
 * <p>锁不可用时<b>照常执行</b>而不是跳过：重复执行的代价是"某个超时动作做了两次"，
 * 跳过的代价是"超时的审批永远没人处理"。前者可恢复，后者会一直卡着。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class SchedulerLease {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLease.class);

    /** 键前缀：与限流计数、会话锁分开，避免不同用途的键互相覆盖。 */
    private static final String KEY_PREFIX = "cw:sched:";

    /** 抢不到立刻放弃——定时任务下一轮还会来。 */
    private static final Duration NO_WAIT = Duration.ZERO;

    private final CustomerWorkProperties properties;
    private final ObjectProvider<DistributedLockExecutor> executorProvider;

    public SchedulerLease(CustomerWorkProperties properties,
                          ObjectProvider<DistributedLockExecutor> executorProvider) {
        this.properties = properties;
        this.executorProvider = executorProvider;
    }

    /**
     * 不加互斥的实例：给离线单测与显式构造的调用方用。
     *
     * <p>返回一个直接执行的实现而不是 {@code null}——调用点写成
     * {@code lease == null ? task.run() : lease.run(...)} 就等于把这个判断复制到每个调度器里，
     * 而漏掉一处不会报错，只会在多副本下重复执行。</p>
     */
    public static SchedulerLease noLease() {
        return new SchedulerLease(null, null) {
            @Override
            public void runExclusively(String name, Runnable task) {
                task.run();
            }
        };
    }

    /**
     * 以互斥方式执行一轮定时任务。
     *
     * @param name 任务名，作为锁键；同名任务在集群内同一时刻只跑一个副本
     */
    public void runExclusively(String name, Runnable task) {
        if (!properties.getDistributed().isSchedulerLeaseEnabled()) {
            task.run();
            return;
        }
        DistributedLockExecutor executor = executorProvider.getIfAvailable();
        if (executor == null) {
            // 开了开关却没有锁执行器：照常执行而不是跳过——
            // 重复执行可恢复，而跳过会让超时的审批永远没人处理
            log.error("scheduler lease enabled but no lock executor available, "
                + "running without exclusion, code={} task={}", "SCHED-LEASE-EXECUTOR-MISSING", name);
            task.run();
            return;
        }
        Duration lease = Duration.ofSeconds(properties.getDistributed().getSchedulerLeaseSeconds());
        try {
            executor.execute(KEY_PREFIX + name, NO_WAIT, lease, task);
        } catch (Exception e) {
            // 抢锁失败（另一个副本正在跑）与锁服务故障在这里合流：
            // 前者本就该跳过，后者跳过一轮也比两个副本同时改状态安全
            log.info("scheduler round skipped, task={} reason={}", name, e.getMessage());
        }
    }
}
