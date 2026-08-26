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
            "admin.customer-work-db.schema-migration-enabled"));
        // 客服端库连接必须可被环境变量覆盖，且与 app-server 连同一个库时共用同一套变量名。
        // 此前这五项散在三个前缀下且无占位，于是既不在部署手册也不在 k8s 清单里。
        assertEquals("${MYSQL_HOST:localhost}", property(sources, "admin.customer-work-db.host"));
        assertEquals("${MYSQL_PORT:3306}", property(sources, "admin.customer-work-db.port"));
        assertEquals("${MYSQL_DATABASE:agent_scope_customer_work}",
            property(sources, "admin.customer-work-db.database"));
        assertEquals("${MYSQL_USERNAME:root}", property(sources, "admin.customer-work-db.username"));
        assertEquals("${MYSQL_PASSWORD:root}", property(sources, "admin.customer-work-db.password"));
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
        // 「客服端生产库由 DBA 手工变更、Admin 不持有 DDL 权限」此前要靠三个前缀各写一行来守，
        // 新增第四个跨库能力域时漏掉就会让 admin 对生产库跑 Flyway。收敛成单一连接后只需这一行。
        assertEquals(Boolean.FALSE, property(sources,
            "admin.customer-work-db.schema-migration-enabled"));
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
