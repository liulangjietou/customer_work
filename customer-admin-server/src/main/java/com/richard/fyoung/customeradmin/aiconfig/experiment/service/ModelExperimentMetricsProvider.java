package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;

/** 在线实验指标聚合端口。实现必须只返回可按实验版本和实验臂证明归属的真实数据。 */
public interface ModelExperimentMetricsProvider {

    ModelExperimentMetricsSnapshot snapshot(AiModelExperiment experiment);
}
