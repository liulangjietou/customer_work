package com.richard.fyoung.customeradmin.auth.controller;

import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.config.SaTokenConfig;
import com.richard.fyoung.customeradmin.system.user.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = AuthControllerRegisterOptionsTest.WebMvcTestConfig.class)
class AuthControllerRegisterOptionsTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private RegistrationGuard registrationGuard;

    @Test
    void registerOptions_shouldExposeTheCompleteAnonymousRegistrationJsonContract() throws Exception {
        when(registrationGuard.selfServiceEnabled()).thenReturn(true);
        when(registrationGuard.captchaRequired()).thenReturn(true);
        when(registrationGuard.emailRequired()).thenReturn(false);
        when(registrationGuard.emailVerificationRequired()).thenReturn(true);
        when(registrationGuard.emailCodeResendCooldownSeconds()).thenReturn(37);
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();

        mockMvc.perform(get("/api/auth/register-options").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.message").value("ok"))
            .andExpect(jsonPath("$.data.selfServiceEnabled").value(true))
            .andExpect(jsonPath("$.data.captchaRequired").value(true))
            .andExpect(jsonPath("$.data.emailRequired").value(false))
            .andExpect(jsonPath("$.data.emailVerificationRequired").value(true))
            .andExpect(jsonPath("$.data.emailCodeCooldownSeconds").value(37));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({AuthController.class, SaTokenConfig.class})
    static class WebMvcTestConfig {

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        UserRegistrationService userRegistrationService() {
            return mock(UserRegistrationService.class);
        }

        @Bean
        RegistrationGuard registrationGuard() {
            return mock(RegistrationGuard.class);
        }

        @Bean
        RegistrationGuardProperties registrationGuardProperties() {
            return new RegistrationGuardProperties();
        }
    }
}
