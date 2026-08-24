package com.richard.fyoung.customeradmin.aiconfig.model.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 模型持续健康巡检配置。默认关闭，避免未评估预算前自动产生外部模型调用费用。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.model-health")
public class ModelHealthMonitorProperties {

    private boolean enabled = false;
    private long scanIntervalMs = 60000L;
    private int batchSize = 20;
    private int workerCount = 8;
    private int queueCapacity = 32;
    private long probeTimeoutSeconds = 10L;
    /** 连续失败达到该值后从路由候选剔除。 */
    private int failureThreshold = 3;
    /** UNHEALTHY 冷却后连续成功达到该值才恢复路由。 */
    private int recoveryThreshold = 2;
    /** 常规探测间隔。 */
    private long probeIntervalSeconds = 300L;
    /** 进入 UNHEALTHY 后至少等待该时长再自动探测。 */
    private long cooldownSeconds = 60L;
    /** 人工强制覆盖的最大有效时长。 */
    private int maxOverrideHours = 24;
}
