package com.richard.fyoung.customerwork.infra.config;

import com.xxl.job.core.executor.XxlJobExecutor;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.xxljob.XxlJobAgentScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import com.richard.fyoung.customerwork.infra.config.properties.SchedulerProperties;

/**
 * 基于 AgentScope 的定时任务调度接入（对应特性「定时任务」，XXL-JOB 实现）。
 *
 * <p>调度周期（cron / 固定频率）、启停、失败重试<b>统一在 XXL-JOB 控制台管理</b>——本类只在启动时
 * 把 {@code customer-work.scheduler.tasks[]} 配置的固定任务列表，逐个注册为 XXL-JOB JobHandler
 * （handler 名 = 任务 {@code name}，需与控制台"任务管理"里配置的 JobHandler 名一致）。官方
 * {@link XxlJobAgentScheduler} 当前只接受 {@link io.agentscope.extensions.scheduler.config.ScheduleMode#NONE}
 * （即不由本进程内部计时），避免应用内定时器与控制台配置周期"两处真相"互相打架。</p>
 *
 * <p>触发时，控制台"任务参数"（jobParam）会作为一条用户消息文本传给 Agent，可用于每次触发时临时
 * 覆盖/补充输入（不传则 Agent 仅凭系统提示词自行发挥）。</p>
 *
 * <p>默认关闭（{@code customer-work.scheduler.xxl-job.enabled=false}），本地无 XXL-JOB 调度中心
 * 也能正常启动；开启后 {@link XxlJobExecutor} 会真实起 Netty 端口并尝试连接调度中心。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "customer-work.scheduler.xxl-job", name = "enabled", havingValue = "true")
public class XxlJobSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(XxlJobSchedulerConfig.class);

    private final CustomerWorkProperties properties;
    private final Model chatModel;

    public XxlJobSchedulerConfig(CustomerWorkProperties properties, Model chatModel) {
        this.properties = properties;
        this.chatModel = chatModel;
    }

    /** XXL-JOB 执行器：随 Spring 容器生命周期 start/destroy，向调度中心注册/注销自身。 */
    @Bean(initMethod = "start", destroyMethod = "destroy")
    public XxlJobExecutor xxlJobExecutor() {
        SchedulerProperties.XxlJob cfg = properties.getScheduler().getXxlJob();
        XxlJobExecutor executor = new XxlJobExecutor();
        executor.setAdminAddresses(cfg.getAdminAddresses());
        executor.setAppname(cfg.getAppname());
        executor.setPort(cfg.getPort());
        executor.setAccessToken(cfg.getAccessToken());
        executor.setLogPath(cfg.getLogPath());
        log.info("xxl-job executor initializing, appname={}, port={}", cfg.getAppname(), cfg.getPort());
        return executor;
    }

    /**
     * AgentScope 的 XXL-JOB 调度适配器；装配完成后立即把固定任务列表注册为 JobHandler
     * （复用同一个 {@link Model} 实例——{@code chatModel} 本身是无状态的请求构建器，多任务共享安全）。
     */
    @Bean
    public AgentScheduler agentScheduler(XxlJobExecutor xxlJobExecutor) {
        AgentScheduler scheduler = new XxlJobAgentScheduler(xxlJobExecutor);
        registerTasks(scheduler);
        return scheduler;
    }

    private void registerTasks(AgentScheduler scheduler) {
        var tasks = properties.getScheduler().getTasks();
        if (CollectionUtils.isEmpty(tasks)) {
            log.info("xxl-job scheduler ready, no fixed task configured (scheduler.tasks is empty)");
            return;
        }
        ModelConfig modelConfig = wrapModel(chatModel);
        for (SchedulerProperties.Task task : tasks) {
            RuntimeAgentConfig agentConfig = RuntimeAgentConfig.builder()
                .name(task.getName())
                .sysPrompt(task.getSysPrompt())
                .modelConfig(modelConfig)
                .build();
            scheduler.schedule(agentConfig, ScheduleConfig.builder().build());
            log.info("scheduled task registered as xxl-job handler, taskName={}", task.getName());
        }
    }

    /**
     * 把已装配好的 {@link Model} Bean 适配成 {@link ModelConfig}——scheduler-common 的
     * {@code RuntimeAgentConfig.Builder} 只接受 {@link ModelConfig}（内部靠它 {@code createModel()}），
     * 不直接接受现成的 {@link Model} 实例；这里原样返回复用同一实例，不重复构建模型客户端。
     */
    private ModelConfig wrapModel(Model model) {
        return new ModelConfig() {
            @Override
            public String getModelName() {
                return model.getModelName();
            }

            @Override
            public Model createModel() {
                return model;
            }
        };
    }
}
