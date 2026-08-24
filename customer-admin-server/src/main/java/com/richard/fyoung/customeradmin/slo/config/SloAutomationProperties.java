package com.richard.fyoung.customeradmin.slo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SLO 周期评估与可靠通知 Worker 配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.slo.automation")
public class SloAutomationProperties {

    private boolean enabled = true;
    private long scanIntervalMs = 30000L;
    private long evaluationIntervalMs = 60000L;
    private long evaluationLeaseMs = 120000L;
    private int evaluationBatchSize = 50;
    private long notificationScanIntervalMs = 5000L;
    private long notificationLeaseMs = 30000L;
    private int notificationBatchSize = 50;
    private long notificationBaseBackoffMs = 1000L;
    private long notificationMaxBackoffMs = 60000L;
}
