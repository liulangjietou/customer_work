package com.richard.fyoung.customerwork.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * infra 域自动装配:基础设施(运行时配置/持久化环境/限流计数与会话锁 SPI/网关注册/WS 基建/通知/健康诊断)。
 *
 * <p>以 {@link OnCustomerWorkEntryCondition} 联动入口:exclude 入口类即整体关闭(下游既有 exclude 行为不变)。
 * 单独裁剪本域用 {@code customer-work.modules.infra.enabled=false}(默认开启)。注意 data/capability
 * 等域的 MyBatis Store 实现依赖本域的独立持久化环境,关闭本域会让它们回落内存实现或启动失败。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@AutoConfiguration(after = CustomerWorkAutoConfiguration.class)
@Conditional(OnCustomerWorkEntryCondition.class)
@ConditionalOnProperty(prefix = "customer-work.modules.infra", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.richard.fyoung.customerwork.infra")
public class CustomerWorkInfraAutoConfiguration {
}
