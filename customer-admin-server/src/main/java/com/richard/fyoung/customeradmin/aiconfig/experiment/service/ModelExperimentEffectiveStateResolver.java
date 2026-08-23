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
        if (RuntimePublishStatus.APPLIED.name().equals(task.getStatus())) {
            return TaskOutcome.APPLIED;
        }
        if (RuntimePublishStatus.PENDING.name().equals(task.getStatus())
            || RuntimePublishStatus.PROCESSING.name().equals(task.getStatus())
            || RuntimePublishStatus.PUBLISHED.name().equals(task.getStatus())) {
            return TaskOutcome.IN_PROGRESS;
        }
        // PARTIAL 表示已有实例拒绝或尚未形成一致生效事实，不能把局部成功冒充整体 ACTIVE/INACTIVE。
        return TaskOutcome.FAILED;
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
