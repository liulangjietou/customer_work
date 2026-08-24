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
    void shouldEnableAiCodingFeaturesByDefaultOutsideProduction() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "application", new ClassPathResource("application.yml"));

        assertEquals("${ADMIN_SANDBOX_COMMAND_EXECUTION_ENABLED:true}", property(sources,
            "admin.sandbox.features.command-execution-enabled"));
        assertEquals("${ADMIN_SANDBOX_DIAGNOSIS_ENABLED:true}", property(sources,
            "admin.sandbox.features.diagnosis-enabled"));
        assertEquals("${ADMIN_SANDBOX_REFACTOR_ENABLED:true}", property(sources,
            "admin.sandbox.features.refactor-enabled"));
        assertEquals("${ADMIN_SANDBOX_MANAGEMENT_ENABLED:true}", property(sources,
            "admin.sandbox.features.management-enabled"));
        assertEquals("${ADMIN_A2A_TOKEN:111111}", property(sources, "admin.a2a.token"));
        assertEquals("${ADMIN_CUSTOMER_WORK_SCHEMA_MIGRATION_ENABLED:true}", property(sources,
            "admin.content-guard.schema-migration-enabled"));
    }

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
        assertEquals(Boolean.FALSE, property(sources,
            "admin.sandbox.features.command-execution-enabled"));
        assertEquals(Boolean.FALSE, property(sources,
            "admin.sandbox.features.diagnosis-enabled"));
        assertEquals(Boolean.FALSE, property(sources,
            "admin.sandbox.features.refactor-enabled"));
        assertEquals(Boolean.FALSE, property(sources,
            "admin.sandbox.features.management-enabled"));
        assertEquals("${ADMIN_A2A_TOKEN:}", property(sources, "admin.a2a.token"));
        assertEquals(Boolean.FALSE, property(sources,
            "admin.content-guard.schema-migration-enabled"));
        assertEquals(Boolean.FALSE, property(sources, "admin.dict.schema-migration-enabled"));
        assertEquals(Boolean.FALSE, property(sources,
            "admin.agent-call-stats.app.schema-migration-enabled"));
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
