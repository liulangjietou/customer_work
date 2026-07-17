package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity.AiScheduledTask;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.mapper.AiScheduledTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service.ScheduledTaskService;
import com.richard.fyoung.customeradmin.config.AdminSchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 内置动态定时任务调度器（internal 调度模式）：用 {@link ThreadPoolTaskScheduler} + Spring
 * {@link CronTrigger} 按任务 cron 周期执行，无需外部 XXL-JOB。
 *
 * <p>生命周期：
 * <ul>
 *   <li>启动（{@link ContextRefreshedEvent}）：加载全部 enabled 且有 cron 的任务注册；</li>
 *   <li>任务增删改/启停：{@code ScheduledTaskService} 发布 {@link ScheduledTaskChangedEvent}，
 *       本类监听后动态重注册/取消；</li>
 *   <li>执行：复用 {@link ScheduledTaskService#execute}（与手动触发/XXL-JOB 同一条执行链路），
 *       执行历史照旧落 ai_scheduled_task_run。</li>
 * </ul></p>
 *
 * <p>防重叠：同一任务上一次还在执行时，本次直接跳过并 log.info（同步长任务场景避免堆积）。
 * xxl-job 调度模式下本类不注册任何任务（周期以 XXL-JOB 控制台为准），从根上杜绝双跑。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ScheduledTaskScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskScheduler.class);

    private static final int ENABLED = 1;

    private final AiScheduledTaskMapper taskMapper;
    private final ScheduledTaskService scheduledTaskService;
    private final AdminSchedulerProperties properties;

    private final ThreadPoolTaskScheduler taskScheduler;
    /** 已注册任务的调度句柄，key=任务ID，用于动态取消。 */
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    /** 正在执行中的任务ID，用于防重叠。 */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public ScheduledTaskScheduler(AiScheduledTaskMapper taskMapper, ScheduledTaskService scheduledTaskService,
                                  AdminSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.scheduledTaskService = scheduledTaskService;
        this.properties = properties;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(properties.getPoolSize());
        this.taskScheduler.setThreadNamePrefix("sched-task-");
        this.taskScheduler.setWaitForTasksToCompleteOnShutdown(false);
        this.taskScheduler.initialize();
    }

    /**
     * 应用上下文刷新完成后加载存量任务（此时 DataSource/Flyway 已就绪）。
     * 持久层异常兜底 catch(Exception)：加载失败只损失内置周期调度（手动触发不受影响），不阻断应用启动。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void loadOnStartup() {
        if (!properties.isInternalMode()) {
            log.info("scheduler in xxl-job mode, internal dynamic scheduling disabled, mode={}", properties.getMode());
            return;
        }
        try {
            // 幂等防护：ContextRefreshedEvent 在存在子上下文/上下文重启时可能二次触发，
            // 先取消全部已注册句柄再全量重载，避免旧 ScheduledFuture 悬挂导致同一任务双跑
            futures.keySet().forEach(this::cancel);
            List<AiScheduledTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<AiScheduledTask>().eq(AiScheduledTask::getEnabled, ENABLED));
            if (CollectionUtils.isEmpty(tasks)) {
                log.info("internal scheduler started with no schedulable task");
                return;
            }
            int registered = 0;
            for (AiScheduledTask task : tasks) {
                if (schedule(task)) {
                    registered++;
                }
            }
            log.info("internal scheduler started, registeredCount={}", registered);
        } catch (Exception e) {
            log.error("internal scheduler startup load failed, code={}", "SCHED-TASK-STARTUP-LOAD-FAIL", e);
        }
    }

    /** 监听任务变更事件，动态重注册/取消。 */
    @EventListener
    public void onTaskChanged(ScheduledTaskChangedEvent event) {
        reconcile(event.taskId(), event.removed());
    }

    /**
     * 对齐单个任务的调度状态：先取消旧句柄，删除则结束；否则按最新 enabled + cron 决定是否重注册。
     * xxl-job 模式下 {@link #schedule} 会直接跳过，故本方法任何模式下都可安全调用。
     */
    void reconcile(Long taskId, boolean removed) {
        cancel(taskId);
        if (removed) {
            return;
        }
        AiScheduledTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        schedule(task);
    }

    /** 注册单个任务；成功注册返回 true。仅 internal 模式 + enabled + cron 合法时才注册。 */
    private boolean schedule(AiScheduledTask task) {
        if (!properties.isInternalMode()) {
            return false;
        }
        if (task.getEnabled() == null || task.getEnabled() != ENABLED) {
            return false;
        }
        if (!StringUtils.hasText(task.getCron())) {
            return false;
        }
        CronTrigger trigger;
        try {
            trigger = new CronTrigger(task.getCron().trim());
        } catch (IllegalArgumentException e) {
            // cron 入库时已校验，此处仅兜底存量脏数据，skip 不影响其它任务
            log.error("internal scheduler skip invalid cron, code={}, taskId={}, cron={}",
                "SCHED-TASK-CRON-INVALID", task.getId(), task.getCron(), e);
            return false;
        }
        Long taskId = task.getId();
        String taskCode = task.getTaskCode();
        ScheduledFuture<?> future = taskScheduler.schedule(() -> runOnce(taskId, taskCode), trigger);
        if (future != null) {
            futures.put(taskId, future);
        }
        log.info("internal scheduler registered task, taskId={}, taskCode={}, cron={}", taskId, taskCode, task.getCron());
        return true;
    }

    /** 取消任务调度句柄（若存在）。 */
    private void cancel(Long taskId) {
        ScheduledFuture<?> future = futures.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("internal scheduler cancelled task, taskId={}", taskId);
        }
    }

    /** 单次触发：防重叠 + 复用统一执行入口；execute 内部已落执行历史并吞异常，这里再兜底一层。 */
    void runOnce(Long taskId, String taskCode) {
        if (!running.add(taskId)) {
            log.info("internal scheduler skip overlapping run, taskId={}, taskCode={}", taskId, taskCode);
            return;
        }
        try {
            scheduledTaskService.execute(taskCode, ScheduledTaskService.TRIGGER_TYPE_INTERNAL);
        } catch (Exception e) {
            log.error("internal scheduler run failed, code={}, taskId={}, taskCode={}",
                "SCHED-TASK-INTERNAL-RUN-FAIL", taskId, taskCode, e);
        } finally {
            running.remove(taskId);
        }
    }

    @Override
    public void destroy() {
        taskScheduler.shutdown();
    }

    /** 仅供测试观测：当前已注册的任务ID集合。 */
    Set<Long> registeredTaskIds() {
        return futures.keySet();
    }

    /** 仅供测试观测：某任务当前的调度句柄（未注册返回 null）。 */
    ScheduledFuture<?> futureOf(Long taskId) {
        return futures.get(taskId);
    }
}
