package com.richard.fyoung.customerwork.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CrossDbConnectionSettings} 单测：默认池化参数与必填校验（配置写错要在建池前就炸，不能拖到建连时）。
 * @author owlzhangfq@gmail.com
 */
class CrossDbConnectionSettingsTest {

    private static final String URL = "jdbc:mysql://localhost:3306/agent_scope_customer_work";

    @Test
    void builder_shouldApplyDefaults() {
        CrossDbConnectionSettings settings = CrossDbConnectionSettings.builder("demo-pool", URL)
            .credentials("root", "root")
            .build();

        assertEquals("demo-pool", settings.poolName());
        assertEquals(URL, settings.jdbcUrl());
        assertEquals(CrossDbConnectionSettings.DEFAULT_DRIVER_CLASS, settings.driverClassName());
        assertEquals(3, settings.maximumPoolSize());
        assertEquals(0, settings.minimumIdle());
        assertEquals(5000L, settings.connectionTimeoutMs());
        assertFalse(settings.readOnly());
    }

    @Test
    void builder_shouldOverrideDefaults() {
        CrossDbConnectionSettings settings = CrossDbConnectionSettings.builder("demo-pool", URL)
            .credentials("u", "p")
            .driverClassName("org.h2.Driver")
            .maximumPoolSize(2)
            .minimumIdle(1)
            .connectionTimeoutMs(1500L)
            .readOnly(true)
            .build();

        assertEquals("org.h2.Driver", settings.driverClassName());
        assertEquals(2, settings.maximumPoolSize());
        assertEquals(1, settings.minimumIdle());
        assertEquals(1500L, settings.connectionTimeoutMs());
        assertTrue(settings.readOnly());
    }

    @Test
    void constructor_shouldRejectBlankPoolNameOrUrl() {
        assertThrows(IllegalArgumentException.class,
            () -> CrossDbConnectionSettings.builder(" ", URL).build());
        assertThrows(IllegalArgumentException.class,
            () -> CrossDbConnectionSettings.builder("demo-pool", "").build());
    }
}
