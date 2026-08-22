package com.richard.fyoung.customeradmin.openapi;

import com.richard.fyoung.customeradmin.datascope.DataScopeResolver;
import com.richard.fyoung.customeradmin.datascope.DataScopeWebConfig;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantWebConfig;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 开放 API 的机器凭据上下文不得被同一请求中附带的后台 Sa-Token 覆盖。 */
class OpenApiInterceptorIsolationTest {

    @Test
    void tenantInterceptor_shouldExcludeOpenApiMachineCredentialPath() {
        InterceptorRegistry registry = registry();
        InterceptorRegistration registration = registrationOf(registry);

        new TenantWebConfig(
            mock(CrossTenantAuthority.class), mock(TenantAccessPolicyService.class)).addInterceptors(registry);

        verify(registration).excludePathPatterns(OpenApiWebConfig.PATH_PATTERN);
    }

    @Test
    void dataScopeInterceptor_shouldExcludeOpenApiMachineCredentialPath() {
        InterceptorRegistry registry = registry();
        InterceptorRegistration registration = registrationOf(registry);

        new DataScopeWebConfig(mock(DataScopeResolver.class)).addInterceptors(registry);

        verify(registration).excludePathPatterns(OpenApiWebConfig.PATH_PATTERN);
    }

    private InterceptorRegistry registry() {
        return mock(InterceptorRegistry.class);
    }

    private InterceptorRegistration registrationOf(InterceptorRegistry registry) {
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any())).thenReturn(registration);
        when(registration.addPathPatterns(any(String[].class))).thenReturn(registration);
        when(registration.excludePathPatterns(any(String[].class))).thenReturn(registration);
        when(registration.order(anyInt())).thenReturn(registration);
        return registration;
    }
}
