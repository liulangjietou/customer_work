package com.richard.fyoung.customerwork.autoconfigure;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按域拆分后的装配门控语义测试。
 *
 * <p>覆盖三条契约:①入口类完成属性绑定;②域装配以 {@link OnCustomerWorkEntryCondition} 联动入口——
 * 下游只把入口类加进 {@code spring.autoconfigure.exclude}(admin-server / customer-channel 的既有
 * 配置),全部域装配即整体让位;③{@code customer-work.modules.<域>.enabled=false} 可单独关闭一个域。
 * 两类负向条件都必须在配置类解析期可评估(标记 Bean 方案在解析期评估不到,会静默跳过
 * {@code @ComponentScan},此坑由 app-server 的 ApplicationContextTest 全链路兜底回归)。</p>
 *
 * <p>「默认全开时全链路可用」由现有全量测试套件覆盖(app-server 以完整自动装配整机启动),
 * 此处不重复。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkModularAutoConfigurationTest {

    private static final String ENTRY =
        "com.richard.fyoung.customerwork.autoconfigure.CustomerWorkAutoConfiguration";

    @Test
    void entry_shouldBindProperties() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomerWorkAutoConfiguration.class))
            .run(context -> assertEquals(1,
                context.getBeanNamesForType(CustomerWorkProperties.class).length,
                "入口装配必须完成属性绑定"));
    }

    @Test
    void domainConfig_shouldBackOffEntirely_whenEntryExcludedByProperty() {
        // 模拟 admin-server / customer-channel 场景:yml 里 exclude 入口类
        new ApplicationContextRunner()
            .withPropertyValues("spring.autoconfigure.exclude=" + ENTRY)
            .withConfiguration(AutoConfigurations.of(CustomerWorkObservabilityAutoConfiguration.class))
            .run(context -> {
                assertTrue(context.getStartupFailure() == null,
                    "入口被 exclude 时域装配应整体跳过而不是启动失败");
                assertEquals(0, context.getBeanNamesForType(AuditSink.class).length,
                    "入口被 exclude 时域装配的默认 Bean 不得出现");
            });
    }

    @Test
    void domainConfig_shouldBackOff_whenModuleToggleOff() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                CustomerWorkAutoConfiguration.class,
                CustomerWorkObservabilityAutoConfiguration.class))
            .withPropertyValues("customer-work.modules.observability.enabled=false")
            .run(context -> {
                assertTrue(context.getStartupFailure() == null,
                    "关闭单个域不应影响容器启动");
                assertEquals(1, context.getBeanNamesForType(CustomerWorkProperties.class).length,
                    "入口装配不受域开关影响");
                assertEquals(0, context.getBeanNamesForType(AuditSink.class).length,
                    "域开关关闭后该域装配整体让位");
            });
    }
}
