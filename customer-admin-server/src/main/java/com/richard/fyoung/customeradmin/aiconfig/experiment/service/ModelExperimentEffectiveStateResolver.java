package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentEffectiveState;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** 根据实验引用的不可变发布意图与真实任务状态计算运行时生效态。 */
public final class ModelExperimentEffectiveStateResolver {

    private ModelExperimentEffectiveStateResolver() {
    }

    public static Resolution resolve(AiModelExperiment experiment,
                                     RuntimePublishTask activationTask,
                                     RuntimePublishTask deactivationTask) {
        ModelExperimentStatus lifecycle = ModelExperimentStatus.valueOf(experiment.getStatus());
        if (lifecycle == ModelExperimentStatus.DRAFT) {
            return new Resolution(ModelExperimentEffectiveState.INACTIVE, null);
        }
        if (lifecycle == ModelExperimentStatus.RUNNING) {
            TaskOutcome activation = outcome(experiment, experiment.getActivationTaskId(), activationTask,
                ModelExperimentPublishAction.ACTIVATE);
            return switch (activation) {
                case APPLIED -> new Resolution(ModelExperimentEffectiveState.ACTIVE, activationTask);
                case IN_PROGRESS -> new Resolution(ModelExperimentEffectiveState.ACTIVATING, activationTask);
                case MISSING, FAILED -> new Resolution(
                    ModelExperimentEffectiveState.ACTIVATION_FAILED, activationTask);
            };
        }

        // DRAFT 直接到期从未下发过实验，此时无需撤流，运行时必然处于未激活状态。
        if (!StringUtils.hasText(experiment.getActivationTaskId()) && experiment.getStartedAt() == null) {
            return new Resolution(ModelExperimentEffectiveState.INACTIVE, null);
        }
        TaskOutcome deactivation = outcome(experiment, experiment.getDeactivationTaskId(), deactivationTask,
            ModelExperimentPublishAction.DEACTIVATE);
        return switch (deactivation) {
            case APPLIED -> new Resolution(ModelExperimentEffectiveState.INACTIVE, deactivationTask);
            case IN_PROGRESS -> new Resolution(ModelExperimentEffectiveState.DEACTIVATING, deactivationTask);
            case MISSING, FAILED -> new Resolution(
                ModelExperimentEffectiveState.DEACTIVATION_FAILED, deactivationTask);
        };
    }

    private static TaskOutcome outcome(AiModelExperiment experiment,
                                       String referencedTaskId,
                                       RuntimePublishTask task,
                                       ModelExperimentPublishAction expectedAction) {
        if (!StringUtils.hasText(referencedTaskId)) {
            return TaskOutcome.MISSING;
        }
        if (task == null
            || !Objects.equals(referencedTaskId, task.getId())
            || !Objects.equals(experiment.getTenantId(), task.getTenantId())
            || !Objects.equals(experiment.getId(), task.getExperimentId())
            || !expectedAction.name().equals(task.getExperimentPublishAction())) {
            return TaskOutcome.FAILED;
        }
        if (EvalGateStatus.BLOCKED.name().equals(task.getGateStatus())) {
            return TaskOutcome.FAILED;
        }
        RuntimePublishStatus status = parseStatus(task.getStatus());
        if (status == null) {
            // 库里出现枚举外的值：按最保守的方向判，绝不冒充生效
            return TaskOutcome.FAILED;
        }
        // 穷尽 switch 且不写 default：将来给 RuntimePublishStatus 加值时这里会编译不过，
        // 强制新增者回答"它在'当前是否已生效'这个问题上算什么"。
        // 此前是 if 链 + 兜底 return FAILED，新枚举值会被静默吞成"失败"而无人察觉。
        return switch (status) {
            case APPLIED -> TaskOutcome.APPLIED;
            case PENDING, PROCESSING, PUBLISHED -> TaskOutcome.IN_PROGRESS;
            // 本方法问的是"现在能不能宣布整体生效"，与 RuntimePublishStatus#isAdvancing()
            // 问的"还会不会自行推进"是两个问题，刻意不复用那组判定：
            // PARTIAL 会随 ACK 到齐继续推进（isAdvancing 为真），但此刻已有实例拒绝或尚未形成
            // 一致生效事实，不能把局部成功冒充整体 ACTIVE/INACTIVE；
            // BLOCKED 等人重评或豁免，在这个问题上同样只能答否。
            case PARTIAL, BLOCKED, SUPERSEDED, FAILED -> TaskOutcome.FAILED;
        };
    }

    private static RuntimePublishStatus parseStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return RuntimePublishStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private enum TaskOutcome {
        MISSING,
        IN_PROGRESS,
        APPLIED,
        FAILED
    }

    public record Resolution(ModelExperimentEffectiveState state, RuntimePublishTask referencedTask) {
    }
}
