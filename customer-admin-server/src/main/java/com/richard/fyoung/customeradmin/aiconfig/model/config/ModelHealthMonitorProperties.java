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
}
