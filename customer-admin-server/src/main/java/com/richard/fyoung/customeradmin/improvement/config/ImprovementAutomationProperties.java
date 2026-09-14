package com.richard.fyoung.customeradmin.improvement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 改进闭环发布跟踪与线上效果观察配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.improvement.automation")
public class ImprovementAutomationProperties {

    private boolean enabled = true;
    private long scanIntervalMs = 30000L;
    private long leaseMs = 120000L;
    private int batchSize = 50;
    private long observationWindowMs = 86400000L;
    private int minExposureCalls = 20;
    private int maxRecurrenceSignals = 0;
    private long maxBackoffMs = 300000L;
    /** 一次复评的总时限，包含两组答复与评分；超时只标失败，不自动重复模型调用。 */
    private long reevaluationTimeoutMs = 1800000L;
}
