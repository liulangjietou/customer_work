package com.richard.fyoung.customerwork.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * safety 域自动装配:安全与租户(注入防护/敏感词/多租户/配额)。
 *
 * <p>以 {@link OnCustomerWorkEntryCondition} 联动入口:exclude 入口类即整体关闭(下游既有 exclude 行为不变)。
 * 单独裁剪本域用 {@code customer-work.modules.safety.enabled=false}(默认开启)。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@AutoConfiguration(after = CustomerWorkAutoConfiguration.class)
@Conditional(OnCustomerWorkEntryCondition.class)
@ConditionalOnProperty(prefix = "customer-work.modules.safety", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.richard.fyoung.customerwork.safety")
public class CustomerWorkSafetyAutoConfiguration {
}
