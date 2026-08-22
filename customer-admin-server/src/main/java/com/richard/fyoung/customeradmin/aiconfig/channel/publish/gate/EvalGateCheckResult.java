package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import com.richard.fyoung.customerwork.capability.eval.EvalType;

import java.util.List;

/** 单类评测规则的可审计判定。 */
public record EvalGateCheckResult(
    EvalType evalType,
    String runId,
    String baselineRunId,
    boolean passed,
    List<String> failures,
    List<String> notices
) {

    public EvalGateCheckResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }
}
