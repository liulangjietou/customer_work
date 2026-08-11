package com.richard.fyoung.customerwork.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * data 域自动装配:业务数据与 Store SPI 域(工单/订单/用户/聊天与调用日志/附件/字典/知识/技能/RAG)。
 *
 * <p>以 {@link OnCustomerWorkEntryCondition} 联动入口:exclude 入口类即整体关闭(下游既有 exclude 行为不变)。
 * 单独裁剪本域用 {@code customer-work.modules.data.enabled=false}(默认开启)。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@AutoConfiguration(after = CustomerWorkAutoConfiguration.class)
@Conditional(OnCustomerWorkEntryCondition.class)
@ConditionalOnProperty(prefix = "customer-work.modules.data", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.richard.fyoung.customerwork.data")
public class CustomerWorkDataAutoConfiguration {
}
