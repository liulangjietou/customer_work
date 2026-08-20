package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配门控测试：确认这一批 Bean 真的进了容器，而不是只在单测里被 new 出来过。
 *
 * <p><b>为什么必须有这个测试</b>：语义缓存踩过一模一样的坑——Service 层 21 条用例全绿，
 * 功能却整体静默失效，因为它依赖的 Bean 从来没被装配过（{@code ObjectProvider.getIfAvailable()} 恒为 null）。
 * 只测逻辑照不出"Bean 根本不存在"。</p>
 * @author owlzhangfq@gmail.com
 */
class SubjectQuotaAssemblyTest {

    /**
     * 用响应式 Web 上下文而非普通上下文：{@code SubjectQuotaWebFilter} 带
     * {@code @ConditionalOnWebApplication(type = REACTIVE)}——它是 WebFlux 的过滤器，
     * 在 Servlet 栈（admin）下既不生效也不该装配。客服端 app-server 正是 WebFlux，
     * 测试上下文必须与真实运行环境同类型，否则这里断言的"装配完整"与线上不是一回事。
     */
    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertiesHolder.class, SubjectQuotaConfig.class));

    @Test
    void shouldAssembleAllBeans_withDefaults() {
        runner.run(context -> {
            assertEquals(1, context.getBeanNamesForType(SubjectQuotaLevelStore.class).length);
            assertEquals(1, context.getBeanNamesForType(SubjectQuotaHitStore.class).length);
            assertEquals(1, context.getBeanNamesForType(SubjectQuotaLevelProvider.class).length);
            assertEquals(1, context.getBeanNamesForType(SubjectLevelResolver.class).length);
            assertEquals(1, context.getBeanNamesForType(SubjectQuotaGuard.class).length);
            assertEquals(1, context.getBeanNamesForType(SubjectQuotaWebFilter.class).length,
                "HTTP 判定入口缺了，功能对三类 HTTP 主体就整体不生效");
        });
    }

    @Test
    void guard_shouldBeDisabled_byDefault() {
        runner.run(context -> assertFalse(context.getBean(SubjectQuotaGuard.class).isEnabled(),
            "默认关闭：没开时行为必须与引入本功能之前完全一致"));
    }

    @Test
    void guard_shouldBeEnabled_whenConfigured() {
        runner.withPropertyValues("customer-work.subject-quota.enabled=true")
            .run(context -> assertTrue(context.getBean(SubjectQuotaGuard.class).isEnabled()));
    }

    @Test
    void store_shouldFallBackToMemory_whenJdbcMapperMissing() {
        // 配了 jdbc 却没有 Mapper（持久层没装配）：让位给内存实现而不是启动失败——
        // 限流是旁路保护，不该拖垮主链路的可启动性
        runner.withPropertyValues("customer-work.subject-quota.store-mode=jdbc")
            .run(context -> {
                assertFalse(context.getStartupFailure() != null, "缺 Mapper 不得导致启动失败");
                assertTrue(context.getBean(SubjectQuotaLevelStore.class) instanceof InMemorySubjectQuotaLevelStore);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CustomerWorkProperties.class)
    static class PropertiesHolder {
    }
}
