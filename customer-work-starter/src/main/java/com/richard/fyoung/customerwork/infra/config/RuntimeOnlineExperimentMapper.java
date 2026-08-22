package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.model.experiment.OnlineExperimentSpec;

/** admin/consumer 共享 DTO 到不可变实验领域规格的单点映射。 */
public final class RuntimeOnlineExperimentMapper {

    private RuntimeOnlineExperimentMapper() {
    }

    public static OnlineExperimentSpec toSpec(CustomerWorkRuntimeConfig.OnlineExperiment source) {
        if (source == null || source.getControl() == null || source.getTreatment() == null) {
            throw new IllegalArgumentException("online experiment and both arms are required");
        }
        return new OnlineExperimentSpec(source.getExperimentId(), source.getRevision(),
            source.getAssignmentSalt(), source.getTreatmentBps(), source.getExpiresAtEpochMs(),
            toArm(source.getControl()), toArm(source.getTreatment()));
    }

    private static OnlineExperimentSpec.Arm toArm(CustomerWorkRuntimeConfig.ExperimentArm source) {
        return new OnlineExperimentSpec.Arm(source.getArm(), source.getDeploymentId());
    }
}
