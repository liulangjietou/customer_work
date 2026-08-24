package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.scheduler;

import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity.AiScheduledTask;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service.ScheduledTaskService;
import com.richard.fyoung.customeradmin.config.AdminSchedulerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduledTaskScheduler} 单测：注册/取消生命周期、调度模式门控、防重叠。
 * 不依赖数据库，不做真实 cron 等待（注册断言看内部句柄表，防重叠用 latch 模拟并发）。
 * @author owlzhangfq@gmail.com
 */
class ScheduledTaskSchedulerTest {

    private AiScheduledTaskMapper taskMapper;
    private ScheduledTaskService service;
    private ScheduledTaskScheduler scheduler;

    private ScheduledTaskScheduler newScheduler(String mode) {
        taskMapper = mock(AiScheduledTaskMapper.class);
        service = mock(ScheduledTaskService.class);
        AdminSchedulerProperties properties = new AdminSchedulerProperties();
        properties.setMode(mode);
        return new ScheduledTaskScheduler(taskMapper, service, properties);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    private AiScheduledTask task(Long id, String cron, int enabled) {
        AiScheduledTask task = new AiScheduledTask();
        task.setId(id);
        task.setTaskCode("code-" + id);
        task.setCron(cron);
        task.setEnabled(enabled);
        return task;
    }

    @Test
    void reconcile_shouldRegisterEnabledTaskWithCron() {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "0 0 * * * ?", 1));

        scheduler.reconcile(1L, false);

        assertTrue(scheduler.registeredTaskIds().contains(1L));
    }

    @Test
    void reconcile_shouldNotRegisterWhenCronBlank() {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, null, 1));

        scheduler.reconcile(1L, false);

        assertFalse(scheduler.registeredTaskIds().contains(1L));
    }

    @Test
    void reconcile_shouldNotRegisterWhenDisabled() {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "0 0 * * * ?", 0));

        scheduler.reconcile(1L, false);

        assertFalse(scheduler.registeredTaskIds().contains(1L));
    }

    @Test
    void reconcile_shouldSkipInvalidCronWithoutThrowing() {
        // 存量脏数据兜底：非法 cron 不注册也不抛异常，不影响其它任务
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "not-a-cron", 1));

        scheduler.reconcile(1L, false);

        assertFalse(scheduler.registeredTaskIds().contains(1L));
    }

    @Test
    void reconcile_shouldCancelWhenRemoved() {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "0 0 * * * ?", 1));
        scheduler.reconcile(1L, false);
        assertTrue(scheduler.registeredTaskIds().contains(1L));

        scheduler.reconcile(1L, true);

        assertFalse(scheduler.registeredTaskIds().contains(1L));
    }

    @Test
    void reconcile_shouldReRegisterByLatestState() {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "0 0 * * * ?", 1));
        scheduler.reconcile(1L, false);

        // 停用后再对齐：应从注册表移除，不残留旧句柄
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "0 0 * * * ?", 0));
        scheduler.reconcile(1L, false);

        assertFalse(scheduler.registeredTaskIds().contains(1L));
    }

    @Test
    void reconcile_shouldBeNoOpInXxlJobMode() {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_XXL_JOB);
        when(taskMapper.selectById(1L)).thenReturn(task(1L, "0 0 * * * ?", 1));

        scheduler.reconcile(1L, false);

        assertTrue(scheduler.registeredTaskIds().isEmpty());
    }

    @Test
    void loadOnStartup_shouldBeIdempotent_whenTriggeredTwice() {
        // ContextRefreshedEvent 在存在子上下文/上下文重启时可能二次触发：旧句柄必须被取消，不能悬挂双跑
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, "0 0 * * * ?", 1)));

        scheduler.loadOnStartup();
        ScheduledFuture<?> firstFuture = scheduler.futureOf(1L);
        assertTrue(scheduler.registeredTaskIds().contains(1L));

        scheduler.loadOnStartup();

        assertTrue(firstFuture.isCancelled());
        assertTrue(scheduler.registeredTaskIds().contains(1L));
        assertFalse(scheduler.futureOf(1L).isCancelled());
    }

    @Test
    void runOnce_shouldSkipOverlappingRun() throws Exception {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        when(service.executeFromScheduler(anyString(), eq(ScheduledTaskService.TRIGGER_TYPE_INTERNAL)))
            .thenAnswer(inv -> {
                invocations.incrementAndGet();
                started.countDown();
                proceed.await(2, TimeUnit.SECONDS);
                return null;
            });

        // 线程1：进入执行并卡住（模拟上一次仍在执行中）
        Thread first = new Thread(() -> scheduler.runOnce(1L, "code-1"));
        first.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));

        // 同一任务再次触发：应立即跳过，不再调用 execute
        scheduler.runOnce(1L, "code-1");

        proceed.countDown();
        first.join(2000);
        assertEquals(1, invocations.get());
    }

    @Test
    void runOnce_shouldReleaseRunningFlagAfterFailure() {
        scheduler = newScheduler(AdminSchedulerProperties.MODE_INTERNAL);
        when(service.executeFromScheduler(anyString(), eq(ScheduledTaskService.TRIGGER_TYPE_INTERNAL)))
            .thenThrow(new RuntimeException("boom"))
            .thenReturn(null);

        scheduler.runOnce(1L, "code-1");
        // 第一次失败后 running 标记应释放，第二次不会被误判为重叠
        scheduler.runOnce(1L, "code-1");

        assertEquals(2, org.mockito.Mockito.mockingDetails(service).getInvocations().size());
    }

    @Test
    void runOnce_shouldSkipWhenAnotherPodClaimedSameFireTime() {
        taskMapper = mock(AiScheduledTaskMapper.class);
        service = mock(ScheduledTaskService.class);
        ScheduledTaskClaimStore claimStore = mock(ScheduledTaskClaimStore.class);
        AdminSchedulerProperties properties = new AdminSchedulerProperties();
        scheduler = new ScheduledTaskScheduler(taskMapper, service, properties, claimStore);
        Instant fireTime = Instant.parse("2026-08-23T12:00:00Z");
        when(claimStore.claim("tenant-a", 1L, "code-1", fireTime)).thenReturn(false);

        scheduler.runOnce(1L, "code-1", "tenant-a", fireTime);

        verify(service, never()).executeFromScheduler(anyString(), anyString());
    }
}
