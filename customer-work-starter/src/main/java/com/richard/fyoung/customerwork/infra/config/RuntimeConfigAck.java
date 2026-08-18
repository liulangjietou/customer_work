package com.richard.fyoung.customerwork.infra.config;

/** 单个 8080 实例对某次运行时配置的真实应用回执。 */
public record RuntimeConfigAck(
    String revision,
    String contentHash,
    String instanceId,
    String status,
    String reason,
    long appliedAtMs
) {
}
