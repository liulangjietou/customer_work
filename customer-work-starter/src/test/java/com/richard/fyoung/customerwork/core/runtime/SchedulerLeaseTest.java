package com.richard.fyoung.customerwork.core.runtime;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.lock.DistributedLockExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 定时任务多副本互斥测试。
 *
 * <p><b>守的是什么 bug</b>：队列型调度（Outbox、死信重试）靠表里的 lease_owner 抢占，
 * 多副本天然安全；而状态机型调度器——审批超时、工单 SLA——是「扫一批处于某状态的行，
 * 逐条改状态并触发动作」，没有任何抢占。多副本部署时每个副本都会扫到同一批行：
 * 超时动作执行两次、SLA 告警重复发。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class SchedulerLeaseTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<DistributedLockExecutor> provider(DistributedLockExecutor executor) {
        ObjectProvider<DistributedLockExecutor> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(executor);
        return p;
    }

    private CustomerWorkProperties props(boolean enabled) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getDistributed().setSchedulerLeaseEnabled(enabled);
        return properties;
    }

    @Test
    @DisplayName("默认关闭时直接执行，不依赖锁服务")
    void disabledRunsDirectly() {
        AtomicBoolean ran = new AtomicBoolean();
        new SchedulerLease(props(false), provider(null))
            .runExclusively("t", () -> ran.set(true));

        assertTrue(ran.get(), "单副本部署不该因为没有锁服务就不跑定时任务");
    }

    @Test
    @DisplayName("开启后走分布式锁，且抢不到就立刻放弃不排队")
    void enabledAcquiresLockWithoutWaiting() {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<Duration> wait = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();
        DistributedLockExecutor executor = new DistributedLockExecutor() {
            @Override
            public <T> T execute(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action) {
                key.set(lockKey);
                wait.set(waitTime);
                return action.get();
            }
        };

        new SchedulerLease(props(true), provider(executor))
            .runExclusively("approval-timeout", () -> ran.set(true));

        assertTrue(ran.get());
        assertEquals("cw:sched:approval-timeout", key.get(), "锁键要带前缀，避免与限流/会话锁互相覆盖");
        assertTrue(wait.get().isZero(),
            "抢不到就该跳过这一轮——定时任务下一轮还会来，排队等只会让副本堆在锁上");
    }

    /**
     * 开了开关却没有锁执行器时<b>照常执行</b>，不是跳过。
     *
     * <p>重复执行的代价是"某个超时动作做了两次"，跳过的代价是"超时的审批永远没人处理"。
     * 前者可恢复，后者会一直卡着。</p>
     */
    @Test
    @DisplayName("锁执行器缺失时照常执行而不是跳过")
    void missingExecutorStillRuns() {
        AtomicBoolean ran = new AtomicBoolean();

        new SchedulerLease(props(true), provider(null))
            .runExclusively("t", () -> ran.set(true));

        assertTrue(ran.get(), "跳过会让超时的审批永远没人处理，比重复执行更糟");
    }

    /** 抢锁失败（别的副本正在跑）不该把异常抛给 Spring 的调度线程。 */
    @Test
    @DisplayName("抢锁失败时安静跳过，不抛异常")
    void lockFailureIsSwallowed() {
        DistributedLockExecutor failing = new DistributedLockExecutor() {
            @Override
            public <T> T execute(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action) {
                throw new IllegalStateException("lock held by another node");
            }
        };
        AtomicBoolean ran = new AtomicBoolean();

        new SchedulerLease(props(true), provider(failing))
            .runExclusively("t", () -> ran.set(true));

        assertFalse(ran.get(), "另一个副本正在跑，本轮就该跳过");
    }

    @Test
    @DisplayName("noLease() 直接执行")
    void noLeaseRunsDirectly() {
        AtomicBoolean ran = new AtomicBoolean();

        SchedulerLease.noLease().runExclusively("t", () -> ran.set(true));

        assertTrue(ran.get());
    }
}
