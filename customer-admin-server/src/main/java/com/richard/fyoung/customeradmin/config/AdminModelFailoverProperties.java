package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 智能体模型主备容错熔断参数（见 {@code ModelCircuitBreakerRegistry} / {@code FailoverModel}）。
 * 主模型连续失败达 {@link #failureThreshold} 次即熔断 {@link #openDurationSeconds} 秒，
 * 期间请求直接走备模型；熔断到期自动半开（重置计数、允许再次尝试主模型）。
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.model.failover")
public class AdminModelFailoverProperties {

    /** 熔断触发阈值：单个模型连续失败达到该次数即打开熔断。 */
    private int failureThreshold = 3;

    /** 熔断打开后的持续时长（秒），期间该模型被跳过。 */
    private int openDurationSeconds = 60;
}
