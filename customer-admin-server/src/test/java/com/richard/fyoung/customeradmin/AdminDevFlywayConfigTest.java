package com.richard.fyoung.customeradmin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** admin 本地开发环境 Flyway 跨分支兼容配置门禁。 */
class AdminDevFlywayConfigTest {

    @Test
    void shouldIgnoreMissingAndFutureMigrationsInDevelopment() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "application-dev", new ClassPathResource("application-dev.yml"));

        assertEquals("*:missing", property(sources,
            "spring.flyway.ignore-migration-patterns[0]"));
        assertEquals("*:future", property(sources,
            "spring.flyway.ignore-migration-patterns[1]"));
    }

    private Object property(List<PropertySource<?>> sources, String key) {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
