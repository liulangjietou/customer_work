package com.richard.fyoung.customeradmin.workspace.callstats.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 调用重放的环境闸门；生产默认只允许无外部调用的 MOCK。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.agent-replay")
public class AgentReplayProperties {

    /** 是否允许 DRY_RUN。即便开启，environment 不是 isolated 仍会 fast fail。 */
    private boolean dryRunEnabled = false;
    /** 必须显式配置为 isolated，不能靠请求参数伪造隔离身份。 */
    private String environment = "production";

    public boolean allowsDryRun() {
        return dryRunEnabled && "isolated".equalsIgnoreCase(environment == null ? "" : environment.trim());
    }

    public String dryRunBlockedReason() {
        if (!dryRunEnabled) {
            return "DRY_RUN 未启用；请在独立部署中配置 admin.agent-replay.dry-run-enabled=true";
        }
        if (!"isolated".equalsIgnoreCase(environment == null ? "" : environment.trim())) {
            return "DRY_RUN 仅允许 admin.agent-replay.environment=isolated 的独立部署";
        }
        return null;
    }
}
