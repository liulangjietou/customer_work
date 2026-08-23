package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentEffectiveState;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelExperimentEffectiveStateResolverTest {

    @Test
    void activation_shouldMapPendingAppliedBlockedAndFailedFromReferencedIntent() {
        AiModelExperiment experiment = experiment(ModelExperimentStatus.RUNNING);
        experiment.setActivationTaskId("activate-1");

        assertState(ModelExperimentEffectiveState.ACTIVATING, experiment,
            task("activate-1", ModelExperimentPublishAction.ACTIVATE,
                RuntimePublishStatus.PENDING, EvalGateStatus.PENDING), null);
        assertState(ModelExperimentEffectiveState.ACTIVE, experiment,
            task("activate-1", ModelExperimentPublishAction.ACTIVATE,
                RuntimePublishStatus.APPLIED, EvalGateStatus.PASSED), null);
        assertState(ModelExperimentEffectiveState.ACTIVATION_FAILED, experiment,
            task("activate-1", ModelExperimentPublishAction.ACTIVATE,
                RuntimePublishStatus.BLOCKED, EvalGateStatus.BLOCKED), null);
        assertState(ModelExperimentEffectiveState.ACTIVATION_FAILED, experiment,
            task("activate-1", ModelExperimentPublishAction.ACTIVATE,
                RuntimePublishStatus.FAILED, EvalGateStatus.PASSED), null);
        assertState(ModelExperimentEffectiveState.ACTIVATION_FAILED, experiment,
            task("activate-1", ModelExperimentPublishAction.ACTIVATE,
                RuntimePublishStatus.PARTIAL, EvalGateStatus.PASSED), null);
    }

    @Test
    void activation_shouldRejectAppliedTaskWithWrongExperimentIntent() {
        AiModelExperiment experiment = experiment(ModelExperimentStatus.RUNNING);
        experiment.setActivationTaskId("activate-1");
        RuntimePublishTask wrongIntent = task("activate-1", ModelExperimentPublishAction.DEACTIVATE,
            RuntimePublishStatus.APPLIED, EvalGateStatus.PASSED);

        assertState(ModelExperimentEffectiveState.ACTIVATION_FAILED,
            experiment, wrongIntent, null);
    }

    @Test
    void stopped_shouldRemainDeactivatingOrFailedUntilDeactivationIsApplied() {
        AiModelExperiment experiment = experiment(ModelExperimentStatus.STOPPED);
        experiment.setStartedAt(LocalDateTime.now().minusMinutes(5));
        experiment.setActivationTaskId("activate-1");
        experiment.setDeactivationTaskId("deactivate-1");

        assertState(ModelExperimentEffectiveState.DEACTIVATING, experiment, null,
            task("deactivate-1", ModelExperimentPublishAction.DEACTIVATE,
                RuntimePublishStatus.PENDING, EvalGateStatus.PENDING));
        assertState(ModelExperimentEffectiveState.INACTIVE, experiment, null,
            task("deactivate-1", ModelExperimentPublishAction.DEACTIVATE,
                RuntimePublishStatus.APPLIED, EvalGateStatus.NOT_REQUIRED));
        assertState(ModelExperimentEffectiveState.DEACTIVATION_FAILED, experiment, null,
            task("deactivate-1", ModelExperimentPublishAction.DEACTIVATE,
                RuntimePublishStatus.BLOCKED, EvalGateStatus.BLOCKED));
        experiment.setDeactivationTaskId(null);
        assertState(ModelExperimentEffectiveState.DEACTIVATION_FAILED, experiment, null, null);
    }

    @Test
    void expiredDraftWithoutActivation_shouldBeInactiveWithoutDeactivationTask() {
        AiModelExperiment experiment = experiment(ModelExperimentStatus.COMPLETED);

        assertState(ModelExperimentEffectiveState.INACTIVE, experiment, null, null);
    }

    private void assertState(ModelExperimentEffectiveState expected,
                             AiModelExperiment experiment,
                             RuntimePublishTask activation,
                             RuntimePublishTask deactivation) {
        assertEquals(expected,
            ModelExperimentEffectiveStateResolver.resolve(
                experiment, activation, deactivation).state());
    }

    private AiModelExperiment experiment(ModelExperimentStatus status) {
        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setId(77L);
        experiment.setTenantId("tenant-a");
        experiment.setStatus(status.name());
        return experiment;
    }

    private RuntimePublishTask task(String id,
                                    ModelExperimentPublishAction action,
                                    RuntimePublishStatus status,
                                    EvalGateStatus gateStatus) {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId(id);
        task.setTenantId("tenant-a");
        task.setExperimentId(77L);
        task.setExperimentPublishAction(action.name());
        task.setStatus(status.name());
        task.setGateStatus(gateStatus.name());
        return task;
    }
}
