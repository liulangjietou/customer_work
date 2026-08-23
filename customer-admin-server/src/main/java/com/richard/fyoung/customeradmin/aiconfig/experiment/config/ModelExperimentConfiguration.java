package com.richard.fyoung.customeradmin.aiconfig.experiment.config;

import com.richard.fyoung.customeradmin.aiconfig.experiment.service.ModelExperimentMetricsProvider;
import com.richard.fyoung.customeradmin.aiconfig.experiment.service.ModelExperimentMetricsSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在线实验指标兜底装配；真实调用日志 Provider 不可用时明确返回等待状态。 */
@Configuration(proxyBeanMethods = false)
public class ModelExperimentConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelExperimentMetricsProvider.class)
    public ModelExperimentMetricsProvider awaitingRuntimeExperimentMetricsProvider() {
        return experiment -> ModelExperimentMetricsSnapshot.awaitingRuntime();
    }
}
