package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import java.util.Objects;

/** 受理后的不可变试用事实；冻结配置留在服务端，浏览器由专用视图读取允许的字段。 */
public record AgentDraftTrialRecord(
    AgentDraftTrialScope scope,
    long draftVersion,
    String input,
    String requestFingerprint,
    String configurationFingerprint,
    String frozenConfiguration,
    AgentDraftTrialPhase phase,
    String resultJson,
    String errorCode,
    long acceptedAtMs,
    long deadlineAtMs,
    Long finishedAtMs
) {
    /** 幂等比较只比较原请求；草稿后来变化也不能让同一个标识再次调用模型。 */
    public boolean matches(AgentDraftTrialRequest request) {
        return Objects.equals(requestFingerprint, fingerprint(scope, request));
    }

    /** 只有未过截止时间且尚无终态的原记录可完成，迟到回调不能覆盖未知或最终结果。 */
    public boolean canRecordSuccess(long now) {
        return phase == AgentDraftTrialPhase.RUNNING && now < deadlineAtMs;
    }

    /** 已观察到的失败可以在超时后收尾，但不能覆盖任何已有终态。 */
    public boolean canRecordFailure() {
        return phase == AgentDraftTrialPhase.RUNNING;
    }

    /** 进程中断或终态写入失败后，使用持久截止时间判断是否只能显示结果未知。 */
    public boolean expired(long now) {
        return phase == AgentDraftTrialPhase.RUNNING && now >= deadlineAtMs;
    }

    /** 请求身份覆盖草稿、版本和完整输入，不包含模型凭据。 */
    public static String fingerprint(AgentDraftTrialScope scope, AgentDraftTrialRequest request) {
        return EvalFingerprint.of("agent-draft-trial-request-v1", scope.tenantId(), scope.ownerId(),
            scope.draftId(), request.expectedDraftVersion(), request.input());
    }
}
