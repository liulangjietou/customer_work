package com.richard.fyoung.customerwork.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ComponentScan;

/**
 * core 域自动装配:智能体运行时内核(agent 工厂/运行时/中间件/模型/记忆/会话服务)。
 *
 * <p>以 {@link OnCustomerWorkEntryCondition} 联动入口:exclude 入口类即整体关闭(下游既有 exclude 行为不变)。
 * 本域为运行时内核,不提供单独开关。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@AutoConfiguration(after = CustomerWorkAutoConfiguration.class)
@Conditional(OnCustomerWorkEntryCondition.class)
@ComponentScan("com.richard.fyoung.customerwork.core")
public class CustomerWorkCoreAutoConfiguration {
}
