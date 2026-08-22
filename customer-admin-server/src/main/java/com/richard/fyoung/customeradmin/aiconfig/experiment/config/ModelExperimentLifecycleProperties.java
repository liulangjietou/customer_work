package com.richard.fyoung.customeradmin.aiconfig.experiment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 实验到期与护栏巡检配置；仅访问本地控制面数据，不触发模型调用。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.model-experiment-lifecycle")
public class ModelExperimentLifecycleProperties {

    private boolean enabled = true;
    private long scanIntervalMs = 60000L;
}
