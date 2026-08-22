package com.richard.fyoung.customeradmin.aiconfig.experiment.domain;

/** 在线模型实验生命周期。定义创建后不可编辑，只允许按状态机推进。 */
public enum ModelExperimentStatus {
    DRAFT,
    RUNNING,
    STOPPED,
    COMPLETED
}
