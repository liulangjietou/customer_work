package com.richard.fyoung.customeradmin.tenant.access;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.tenant.access.entity.TenantAccessPublishTask;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAccessPublisherTest {

    @Test
    void springContext_shouldSelectProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RuntimePublishProperties.class);
            context.registerBean(ObjectMapper.class);
            context.register(TenantAccessPublisher.class);

            context.refresh();

            assertTrue(context.getBean(TenantAccessPublisher.class) != null);
        }
    }

    @Test
    void publish_shouldReuseRuntimeNacosConnectionAndEmitAccessProtocol() throws Exception {
        RuntimePublishProperties properties = new RuntimePublishProperties();
        properties.getNacos().setServerAddr("nacos.internal:8848");
        properties.getNacos().setNamespace("customer-work");
        properties.getNacos().setUsername("publisher");
        properties.getNacos().setPassword("secret");
        ConfigService configService = mock(ConfigService.class);
        when(configService.publishConfig(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        AtomicReference<Properties> connection = new AtomicReference<>();
        TenantAccessPublisher publisher = new TenantAccessPublisher(
            properties, new ObjectMapper(), nacosProperties -> {
                connection.set(nacosProperties);
                return configService;
            });
        TenantAccessPublishTask task = task();

        publisher.publish(task);

        assertEquals("nacos.internal:8848", connection.get().get(PropertyKeyConst.SERVER_ADDR));
        assertEquals("customer-work", connection.get().get(PropertyKeyConst.NAMESPACE));
        verify(configService).publishConfig(
            org.mockito.ArgumentMatchers.eq("customer-work-tenant-access-tenant-acme"),
            org.mockito.ArgumentMatchers.eq("DEFAULT_GROUP"),
            org.mockito.ArgumentMatchers.argThat(json -> json.contains("\"schemaVersion\":1")
                && json.contains("\"tenantId\":\"acme\"")
                && json.contains("\"status\":\"SUSPENDED\"")
                && json.contains("\"accessEpoch\":7")));

        publisher.destroy();
        verify(configService).shutDown();
    }

    @Test
    void publishFalse_shouldStayInReliableRetryStateMachine() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.publishConfig(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        TenantAccessPublisher publisher = new TenantAccessPublisher(
            new RuntimePublishProperties(), new ObjectMapper(), ignored -> configService);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> publisher.publish(task()));

        assertTrue(error.getMessage().contains("publishConfig returned false"));
    }

    private TenantAccessPublishTask task() {
        TenantAccessPublishTask task = new TenantAccessPublishTask();
        task.setTenantId("acme");
        task.setTenantStatus("SUSPENDED");
        task.setAccessEpoch(7L);
        task.setDataId("customer-work-tenant-access-tenant-acme");
        task.setGroupName("DEFAULT_GROUP");
        task.setCreatedAtMs(123L);
        return task;
    }
}
