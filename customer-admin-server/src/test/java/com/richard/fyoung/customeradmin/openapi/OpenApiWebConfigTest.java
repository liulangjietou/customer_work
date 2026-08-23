package com.richard.fyoung.customeradmin.openapi;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiWebConfigTest {

    @Test
    void runtimeAckPathShouldUseDedicatedInterceptorAndExcludeGeneralOpenApiToken() {
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration general = mock(InterceptorRegistration.class);
        InterceptorRegistration ack = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any())).thenReturn(general, ack);
        when(general.addPathPatterns(any(String[].class))).thenReturn(general);
        when(general.excludePathPatterns(any(String[].class))).thenReturn(general);
        when(ack.addPathPatterns(any(String[].class))).thenReturn(ack);

        new OpenApiWebConfig(new OpenApiProperties(), new AdminTenantProperties(),
            new RuntimePublishProperties()).addInterceptors(registry);

        ArgumentCaptor<HandlerInterceptor> interceptors = ArgumentCaptor.forClass(HandlerInterceptor.class);
        verify(registry, times(2)).addInterceptor(interceptors.capture());
        assertInstanceOf(OpenApiAuthInterceptor.class, interceptors.getAllValues().get(0));
        assertInstanceOf(RuntimeConfigAckAuthInterceptor.class, interceptors.getAllValues().get(1));
        verify(general).excludePathPatterns(OpenApiWebConfig.RUNTIME_CONFIG_ACK_PATH);
        verify(ack).addPathPatterns(OpenApiWebConfig.RUNTIME_CONFIG_ACK_PATH);
    }
}
