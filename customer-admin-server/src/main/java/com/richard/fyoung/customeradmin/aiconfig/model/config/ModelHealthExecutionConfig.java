package com.richard.fyoung.customeradmin.aiconfig.model.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/** 模型健康探测的有界执行资源，生命周期由 Spring 容器统一管理。 */
@Configuration
public class ModelHealthExecutionConfig {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MAX_WORKER_COUNT = 32;
    private static final int MIN_QUEUE_CAPACITY = 1;
    private static final int MAX_QUEUE_CAPACITY = 1000;

    @Bean("modelHealthProbeExecutor")
    public ThreadPoolTaskExecutor modelHealthProbeExecutor(ModelHealthMonitorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workers = clamp(properties.getWorkerCount(), MIN_WORKER_COUNT, MAX_WORKER_COUNT);
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(clamp(properties.getQueueCapacity(),
            MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY));
        executor.setThreadNamePrefix("model-health-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean("modelHealthTimeoutScheduler")
    public ThreadPoolTaskScheduler modelHealthTimeoutScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("model-health-timeout-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
