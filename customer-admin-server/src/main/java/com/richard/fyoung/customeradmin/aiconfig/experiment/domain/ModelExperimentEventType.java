package com.richard.fyoung.customeradmin.aiconfig.experiment.domain;

/** 追加式实验生命周期事件；既有事件没有更新或删除入口。 */
public enum ModelExperimentEventType {
    START,
    STOP,
    AUTO_STOP,
    EXPIRED
}
