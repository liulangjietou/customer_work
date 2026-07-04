package com.richard.fyoung.customerweb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * customer-web 生产 profile 配置文件（application-prod.yml）语法与关键配置项校验。
 *
 * <p>只做 YAML 语法解析 + 属性绑定校验，不激活该 profile 启动完整应用上下文——
 * 见 {@code customer-work-app} 模块同名测试的说明。</p>
 * @author owlzhangfq@gmail.com
 */
class ProdProfileConfigTest {

    private List<PropertySource<?>> load() throws Exception {
        return new YamlPropertySourceLoader().load("application-prod",
            new ClassPathResource("application-prod.yml"));
    }

    private Object prop(List<PropertySource<?>> sources, String key) {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Test
    void shouldParseWithoutError_andNarrowManagementExposure() throws Exception {
        List<PropertySource<?>> sources = load();
        assertFalse(sources.isEmpty(), "application-prod.yml 应能被正常解析");

        // 生产收敛暴露面：不应再是默认 application.yml 里方便控制台调试的 "*"
        assertEquals("health,prometheus", prop(sources, "management.endpoints.web.exposure.include"));
        assertEquals("when-authorized", prop(sources, "management.endpoint.health.show-details"));
    }
}
