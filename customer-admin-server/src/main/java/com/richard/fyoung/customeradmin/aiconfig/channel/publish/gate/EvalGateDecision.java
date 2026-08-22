package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import java.util.List;

/** 整个发布候选的门禁决策；原样持久化到发布任务用于审计。 */
public record EvalGateDecision(
    EvalGateStatus status,
    List<EvalGateCheckResult> checks,
    long evaluatedAtMs
) {

    public EvalGateDecision {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public boolean allowsPublish() {
        return status == EvalGateStatus.NOT_REQUIRED
            || status == EvalGateStatus.PASSED
            || status == EvalGateStatus.OVERRIDDEN;
    }

    public String summary() {
        return checks.stream()
            .filter(check -> !check.passed())
            .flatMap(check -> check.failures().stream())
            .findFirst()
            .orElse(status.name());
    }
}
