package com.richard.fyoung.customerwork.runtime;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时维护任务（对应「运行时与调度 · Scheduler」的轻量落地）。
 *
 * <p>用 Spring 调度周期性执行运维巡检（在途请求数、会话后端可用性等），输出可观测信号。
 * 仅当 {@code customer-work.runtime.scheduler-enabled=true} 时真正触发。</p>
 *
 * <p>需要"定时驱动 Agent 跑批"（如每日报表、离线评估）时，可接入框架的
 * {@code QuartzAgentScheduler} / {@code XxlJobAgentScheduler}（需引入 Quartz / XXL-JOB 依赖与调度中心）；
 * 工具执行隔离则用独立项目 {@code agentscope-runtime-java} 的沙箱。两者属基础设施扩展点。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final CustomerWorkProperties properties;
    private final AgentStateStore stateStore;
    private final GracefulShutdownService shutdownService;

    public MaintenanceScheduler(CustomerWorkProperties properties,
                                AgentStateStore stateStore,
                                GracefulShutdownService shutdownService) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.shutdownService = shutdownService;
    }

    @Scheduled(fixedDelayString = "${customer-work.runtime.scheduler-fixed-delay-ms:60000}")
    public void scheduledRun() {
        if (!properties.getRuntime().isSchedulerEnabled()) {
            return;
        }
        log.info("[Maintenance] {}", runMaintenance());
    }

    /** 巡检逻辑（抽出以便单测）：汇总会话后端、在途请求、是否接收请求。 */
    String runMaintenance() {
        String backend = properties.getSession().getMode();
        String impl = stateStore.getClass().getSimpleName();
        return String.format("backend=%s impl=%s activeRequests=%d accepting=%s",
            backend, impl, shutdownService.activeRequests(), shutdownService.isAcceptingRequests());
    }
}
