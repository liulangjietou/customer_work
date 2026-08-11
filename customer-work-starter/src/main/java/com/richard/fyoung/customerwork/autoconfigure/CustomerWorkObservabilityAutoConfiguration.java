package com.richard.fyoung.customerwork.autoconfigure;

import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * observability 域自动装配:可观测能力(审计/指标/链路/合成监控/业务分析)。
 *
 * <p>以 {@link OnCustomerWorkEntryCondition} 联动入口:exclude 入口类即整体关闭(下游既有 exclude 行为不变)。
 * 单独裁剪本域用 {@code customer-work.modules.observability.enabled=false}(默认开启)。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@AutoConfiguration(after = CustomerWorkAutoConfiguration.class)
@Conditional(OnCustomerWorkEntryCondition.class)
@ConditionalOnProperty(prefix = "customer-work.modules.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.richard.fyoung.customerwork.observability")
public class CustomerWorkObservabilityAutoConfiguration {

    /**
     * 默认审计落地实现(写专用 logger)。下游声明自己的 {@link AuditSink} Bean 即可覆盖,
     * 把审计轨迹投递到 Kafka / 数据库 / SIEM。
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditSink auditSink() {
        return new LoggingAuditSink();
    }
}
