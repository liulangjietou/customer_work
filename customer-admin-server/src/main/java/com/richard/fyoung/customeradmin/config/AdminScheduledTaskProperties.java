package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务执行参数（与是否接入 XXL-JOB 无关——手动触发也走同一超时兜底）。
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.scheduled-task")
public class AdminScheduledTaskProperties {

    /** 单次任务执行超时（秒）：Agent 同步调用的硬性超时兜底，避免调度/请求线程被永久占用。 */
    private long executeTimeoutSeconds = 300;
}
