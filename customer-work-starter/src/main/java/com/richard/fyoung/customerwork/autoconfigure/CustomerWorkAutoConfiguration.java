package com.richard.fyoung.customerwork.autoconfigure;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * customer-work starter 的自动配置入口。
 *
 * <p>通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册。本类只负责绑定 {@link CustomerWorkProperties} 与开启调度;具体能力按域拆分在
 * {@code CustomerWork<域>AutoConfiguration} 中装配,全部以 {@link OnCustomerWorkEntryCondition}
 * 联动本类——下游把本类加进 {@code spring.autoconfigure.exclude} 即整体关闭 starter 装配
 * (与拆分前行为一致,admin-server / customer-channel 的既有配置无需调整)。</p>
 *
 * <p>各域默认全部开启;需要裁剪时用 {@code customer-work.modules.<域>.enabled=false} 按域关闭
 * (core 为运行时内核,不提供开关)。下游若要覆盖默认实现,声明同类型 Bean 即可
 * (默认实现以 {@code @ConditionalOnMissingBean} 让位)。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@AutoConfiguration
@EnableConfigurationProperties(CustomerWorkProperties.class)
@EnableScheduling
public class CustomerWorkAutoConfiguration {
}
