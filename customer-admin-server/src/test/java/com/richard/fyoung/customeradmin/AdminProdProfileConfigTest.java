package com.richard.fyoung.customeradmin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** admin 生产配置的语法与安全暴露面门控。 */
class AdminProdProfileConfigTest {

    @Test
    void shouldDisableSwaggerAndLimitActuatorByDefault() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "application-prod", new ClassPathResource("application-prod.yml"));

        assertFalse(sources.isEmpty());
        assertEquals("health,prometheus", property(sources,
            "management.endpoints.web.exposure.include"));
        assertEquals("${SPRINGDOC_ENABLED:false}", property(sources,
            "springdoc.api-docs.enabled"));
        assertEquals("${SPRINGDOC_ENABLED:false}", property(sources,
            "springdoc.swagger-ui.enabled"));
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
