package com.richard.fyoung.customeradmin.aiconfig.experiment.domain;

/** 在线实验运行时发布意图；任务处理时不得再从可变生命周期反推。 */
public enum ModelExperimentPublishAction {
    ACTIVATE,
    DEACTIVATE
}
