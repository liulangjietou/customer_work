package com.richard.fyoung.customeradmin.governance.change;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 高风险变更审批与审计留存策略。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.governance")
public class GovernanceProperties {

    /** 待审批请求有效小时数。 */
    private int approvalExpiryHours = 24;
    /** 审计事件最短保留天数。 */
    private int auditRetentionDays = 3650;
    /** 到期请求扫描周期。 */
    private long expiryScanIntervalMs = 60000L;
    /** 执行超过该时长未写终态，收敛为失败并允许重新提交。 */
    private int executionTimeoutSeconds = 600;

    public int effectiveApprovalExpiryHours() {
        return Math.max(1, approvalExpiryHours);
    }

    public int effectiveAuditRetentionDays() {
        return Math.max(365, auditRetentionDays);
    }

    public int effectiveExecutionTimeoutSeconds() {
        return Math.max(60, executionTimeoutSeconds);
    }
}
