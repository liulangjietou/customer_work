package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.job;

import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity.AiScheduledTaskRun;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service.ScheduledTaskService;
import com.richard.fyoung.customeradmin.config.AdminXxlJobProperties;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * XXL-JOB 接入：所有定时任务共用同一个通用 JobHandler（业务标识name={@value #JOB_HANDLER_NAME}），
 * 具体执行哪个任务由 XXL-JOB 控制台"任务参数"（jobParam = {@code task_code}）区分——不需要为每个
 * 业务定时任务单独写一个 JobHandler 类，新增任务只需在后台"定时任务管理"新建一条记录 + 在 XXL-JOB
 * 控制台配一个"任务参数=该 taskCode"的任务即可，零代码改动。
 *
 * <p>默认关闭（{@code admin.xxl-job.enabled=false}），本地无 XXL-JOB 调度中心也能正常启动；
 * 手动触发路径（{@code POST /api/aiconfig/scheduled-task/{id}/trigger}）完全不依赖本类/XXL-JOB。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "admin.xxl-job", name = "enabled", havingValue = "true")
public class ScheduledTaskJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskJobHandler.class);

    /** 通用 JobHandler 在 XXL-JOB 控制台"任务管理"里配置时使用的 JobHandler 名。 */
    public static final String JOB_HANDLER_NAME = "agentScheduledTask";

    private final AdminXxlJobProperties properties;

    public ScheduledTaskJobHandler(AdminXxlJobProperties properties) {
        this.properties = properties;
    }

    /** 本模块独立的 XXL-JOB 执行器：随 Spring 容器生命周期 start/destroy。 */
    @Bean(initMethod = "start", destroyMethod = "destroy")
    public XxlJobExecutor scheduledTaskXxlJobExecutor() {
        XxlJobExecutor executor = new XxlJobExecutor();
        executor.setAdminAddresses(properties.getAdminAddresses());
        executor.setAppname(properties.getAppname());
        executor.setPort(properties.getPort());
        executor.setAccessToken(properties.getAccessToken());
        executor.setLogPath(properties.getLogPath());
        log.info("admin xxl-job executor initializing, appname={}, port={}, logPath={}",
            properties.getAppname(), properties.getPort(), properties.getLogPath());
        return executor;
    }

    /** 装配完成后立即把通用 JobHandler 注册进执行器，注册名固定为 {@link #JOB_HANDLER_NAME}。 */
    @Bean
    public IJobHandler agentScheduledTaskHandler(XxlJobExecutor xxlJobExecutor, ScheduledTaskService scheduledTaskService) {
        IJobHandler handler = new AgentScheduledTaskHandler(scheduledTaskService);
        XxlJobExecutor.registryJobHandler(JOB_HANDLER_NAME, handler);
        log.info("scheduled task xxl-job handler registered, handlerName={}", JOB_HANDLER_NAME);
        return handler;
    }

    /** 从任务参数取 task_code，委托给 {@link ScheduledTaskService#execute} 执行一次，并把结果回写给 XXL-JOB。 */
    static class AgentScheduledTaskHandler extends IJobHandler {

        private final ScheduledTaskService scheduledTaskService;

        AgentScheduledTaskHandler(ScheduledTaskService scheduledTaskService) {
            this.scheduledTaskService = scheduledTaskService;
        }

        @Override
        public void execute() throws Exception {
            String taskCode = XxlJobHelper.getJobParam();
            if (!StringUtils.hasText(taskCode)) {
                log.error("scheduled task xxl-job trigger missing task_code, code={}", "SCHED-TASK-MISSING-PARAM");
                XxlJobHelper.handleFail("任务参数 task_code 不能为空");
                return;
            }
            AiScheduledTaskRun run = scheduledTaskService.execute(taskCode.trim(), ScheduledTaskService.TRIGGER_TYPE_XXL_JOB);
            if (ScheduledTaskService.STATUS_SUCCESS.equals(run.getStatus())) {
                XxlJobHelper.handleSuccess();
            } else {
                XxlJobHelper.handleFail(run.getErrorMessage());
            }
        }
    }
}
