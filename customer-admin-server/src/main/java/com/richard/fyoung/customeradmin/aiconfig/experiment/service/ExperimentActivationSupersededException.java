package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

/** 激活任务执行时实验已不再是对应 Agent 的 RUNNING 实验。 */
public class ExperimentActivationSupersededException extends IllegalStateException {

    public ExperimentActivationSupersededException(Long experimentId, Long agentId) {
        super("experiment activation task superseded, experimentId=" + experimentId
            + ", agentId=" + agentId);
    }
}
