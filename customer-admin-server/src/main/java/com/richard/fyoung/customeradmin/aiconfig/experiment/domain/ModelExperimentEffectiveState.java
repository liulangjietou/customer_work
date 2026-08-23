package com.richard.fyoung.customeradmin.aiconfig.experiment.domain;

/** 在线实验在运行时的真实生效状态，与控制面的期望生命周期分开表达。 */
public enum ModelExperimentEffectiveState {
    INACTIVE,
    ACTIVATING,
    ACTIVE,
    ACTIVATION_FAILED,
    DEACTIVATING,
    DEACTIVATION_FAILED
}
