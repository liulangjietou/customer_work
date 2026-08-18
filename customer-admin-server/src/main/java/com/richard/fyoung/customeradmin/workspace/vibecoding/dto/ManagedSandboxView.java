package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import java.time.Instant;

/** 交互式会话沙箱运行态视图。 */
public record ManagedSandboxView(
        String sessionId,
        String mode,
        String containerId,
        String status,
        Instant createdAt,
        Instant lastActiveAt,
        String command,
        String cpuUsage,
        String memoryUsage,
        Long memoryLimitMb,
        Long cpuLimit) {
}
