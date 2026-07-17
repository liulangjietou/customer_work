package com.richard.fyoung.customerworkapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 生产 profile 配置文件（application-prod.yml）语法与关键配置项校验。
 *
 * <p>只做 YAML 语法解析 + 属性绑定校验，<b>不</b>激活该 profile 启动完整应用上下文——prod 配置
 * 依赖真实 Redis/MySQL/DashScope 凭据，在无这些外部依赖的开发/CI 环境无法真实连接。
 * 本测试保证的是"配置文件本身没写错"，不是"生产环境能跑起来"（后者需上线前实测）。</p>
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
    void shouldParseWithoutError_andExposeExpectedKeys() throws Exception {
        List<PropertySource<?>> sources = load();
        assertFalse(sources.isEmpty(), "application-prod.yml 应能被正常解析");

        // 抽查本轮新增的关键配置项，确认键路径与 CustomerWorkProperties 字段名一致
        assertEquals("jdbc", prop(sources, "customer-work.human-approval.store-mode"));
        assertEquals("jdbc", prop(sources, "customer-work.slot-filling.store-mode"));
        assertEquals("jdbc", prop(sources, "customer-work.dialog.store-mode"));
        assertEquals("true", String.valueOf(prop(sources, "customer-work.security.approval-auth.enabled")));
        assertEquals("health,prometheus", prop(sources, "management.endpoints.web.exposure.include"));
        assertEquals("when-authorized", prop(sources, "management.endpoint.health.show-details"));
    }
}
