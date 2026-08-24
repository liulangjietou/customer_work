package com.richard.fyoung.customeradmin.billing.config;

import com.richard.fyoung.customeradmin.billing.schedule.UsageAggregationDriver;
import com.richard.fyoung.customeradmin.billing.service.UsageAggregationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 计费装配：用量归集驱动仅在显式开启时才起线程。
 *
 * <p>默认关闭。多副本部署下开启前要知悉：每个副本都会跑自己的归集循环。
 * 归集是幂等的（同一天重跑覆盖），并发不会算错数，只是白白重复扫日志；
 * 要严格互斥可在 {@code UsageAggregationDriver} 外面套 {@code DistributedLockExecutor}。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(BillingSettlementProperties.class)
public class BillingConfig {

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "admin.billing.aggregation", name = "enabled", havingValue = "true")
    public UsageAggregationDriver usageAggregationDriver(
        UsageAggregationService aggregationService,
        @Value("${admin.billing.aggregation.interval-ms:21600000}") long intervalMs,
        @Value("${admin.billing.aggregation.backfill-days:3}") int backfillDays) {
        return new UsageAggregationDriver(aggregationService, intervalMs, backfillDays);
    }
}
