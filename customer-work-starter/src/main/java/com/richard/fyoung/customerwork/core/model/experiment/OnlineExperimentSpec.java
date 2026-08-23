package com.richard.fyoung.customerwork.core.model.experiment;

import java.util.Objects;

/** 不可变双臂在线实验运行规格。 */
public record OnlineExperimentSpec(
    Long experimentId,
    Integer revision,
    String assignmentSalt,
    Integer treatmentBps,
    Long expiresAtEpochMs,
    Arm control,
    Arm treatment
) {

    public OnlineExperimentSpec {
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(revision, "revision");
        if (assignmentSalt == null || assignmentSalt.isBlank()) {
            throw new IllegalArgumentException("assignmentSalt is required");
        }
        if (treatmentBps == null || treatmentBps < 1 || treatmentBps > 9999) {
            throw new IllegalArgumentException("treatmentBps must be between 1 and 9999");
        }
        if (expiresAtEpochMs == null || expiresAtEpochMs <= 0) {
            throw new IllegalArgumentException("expiresAtEpochMs is required");
        }
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(treatment, "treatment");
        if (Objects.equals(control.deploymentId(), treatment.deploymentId())) {
            throw new IllegalArgumentException("online experiment arms must use distinct deployments");
        }
    }

    /** 实验臂只保存选路所需的不可变部署身份。 */
    public record Arm(String name, Long deploymentId) {
        public Arm {
            if (!"CONTROL".equals(name) && !"TREATMENT".equals(name)) {
                throw new IllegalArgumentException("online experiment arm must be CONTROL or TREATMENT");
            }
            Objects.requireNonNull(deploymentId, "deploymentId");
        }
    }
}
