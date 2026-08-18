package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/** {@code admin.sandbox.*} 当前生效配置的只读安全视图。 */
public record SandboxConfigView(
        String mode,
        int executeTimeoutSeconds,
        String permissionMode,
        DockerConfig docker,
        GuardConfig guard,
        FeatureConfig features) {

    public record DockerConfig(String image, long memoryMb, long cpuCount, String network) {
    }

    public record GuardConfig(boolean enabled) {
    }

    public record FeatureConfig(
            boolean commandExecutionEnabled,
            boolean diagnosisEnabled,
            boolean refactorEnabled,
            boolean managementEnabled,
            int idleTimeoutMinutes) {
    }
}
