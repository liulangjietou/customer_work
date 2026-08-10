package com.richard.fyoung.customerwork.infra.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XXL-JOB 定时任务调度装配单测（离线，不连接真实调度中心，特性「定时任务」）。
 *
 * <p>只覆盖①属性绑定默认值 ②{@code enabled=false}（含"完全不配置该属性"）时不装配
 * {@link io.agentscope.extensions.scheduler.AgentScheduler}——{@code @ConditionalOnProperty}
 * 未命中时整个 {@link XxlJobSchedulerConfig} 都不会被实例化，自然不会触发
 * {@code XxlJobExecutor.start()} 真实起 Netty 端口连调度中心。{@code enabled=true} 的真实装配
 * 需要真实 XXL-JOB 调度中心，不适合离线单测覆盖，交由集成环境验证。</p>
 * @author owlzhangfq@gmail.com
 */
class XxlJobSchedulerConfigTest {

    @Test
    void xxlJobConfig_shouldHaveSaneDefaults() {
        CustomerWorkProperties.Scheduler.XxlJob cfg = new CustomerWorkProperties().getScheduler().getXxlJob();
        assertFalse(cfg.isEnabled(), "默认不启用 XXL-JOB");
        assertEquals("customer-work-executor", cfg.getAppname());
        assertEquals(9999, cfg.getPort());
        assertEquals("./data/xxl-job/jobhandler", cfg.getLogPath());
    }

    @Test
    void tasks_shouldBeEmpty_byDefault() {
        assertTrue(new CustomerWorkProperties().getScheduler().getTasks().isEmpty());
    }

    @Test
    void executeTimeoutSeconds_shouldDefaultTo300() {
        assertEquals(300, new CustomerWorkProperties().getScheduler().getExecuteTimeoutSeconds());
    }

    @Test
    void context_shouldNotRegisterAgentScheduler_whenXxlJobExplicitlyDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(XxlJobSchedulerConfig.class)
            .withPropertyValues("customer-work.scheduler.xxl-job.enabled=false")
            .run(context -> assertFalse(context.containsBean("agentScheduler"),
                "未开启时不应装配 AgentScheduler，也就不会启动 XxlJobExecutor"));
    }

    @Test
    void context_shouldNotRegisterAgentScheduler_whenPropertyMissing() {
        new ApplicationContextRunner()
            .withUserConfiguration(XxlJobSchedulerConfig.class)
            .run(context -> assertFalse(context.containsBean("agentScheduler")));
    }
}
