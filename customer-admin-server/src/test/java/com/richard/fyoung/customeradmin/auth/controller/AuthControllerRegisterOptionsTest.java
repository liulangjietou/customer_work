package com.richard.fyoung.customeradmin.auth.controller;

import com.richard.fyoung.customeradmin.auth.guard.ClientIpResolver;
import com.richard.fyoung.customeradmin.auth.guard.LoginCaptchaService;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.auth.service.PasswordResetService;
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

    @Autowired
    private PasswordResetService passwordResetService;

    @Test
    void registerOptions_shouldExposeTheCompleteAnonymousRegistrationJsonContract() throws Exception {
        when(registrationGuard.selfServiceEnabled()).thenReturn(true);
        when(registrationGuard.captchaRequired()).thenReturn(true);
        when(registrationGuard.emailCodeResendCooldownSeconds()).thenReturn(37);
        when(passwordResetService.available()).thenReturn(true);
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();

        mockMvc.perform(get("/api/auth/register-options").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.message").value("ok"))
            .andExpect(jsonPath("$.data.selfServiceEnabled").value(true))
            .andExpect(jsonPath("$.data.captchaRequired").value(true))
            .andExpect(jsonPath("$.data.emailCodeCooldownSeconds").value(37))
            .andExpect(jsonPath("$.data.passwordResetEnabled").value(true))
            // 邮箱与邮箱验证码恒为必需，契约里不再出现这两个字段——留一个恒 true 的布尔
            // 等于告诉前端它可能为假，后来的人就会照着写一条永远走不到的分支
            .andExpect(jsonPath("$.data.emailRequired").doesNotExist())
            .andExpect(jsonPath("$.data.emailVerificationRequired").doesNotExist());
    }

    /**
     * 找回密码这一位跟随邮件是否真的可用，没有独立开关。
     *
     * <p>它与自助注册也彼此独立：关掉注册的内网实例照样需要找回密码，
     * 把两者绑在一起会让那些实例的用户彻底没有出路。</p>
     */
    @Test
    void registerOptions_shouldReportPasswordResetSeparatelyFromSelfService() throws Exception {
        when(registrationGuard.selfServiceEnabled()).thenReturn(false);
        when(registrationGuard.captchaRequired()).thenReturn(false);
        when(registrationGuard.emailCodeResendCooldownSeconds()).thenReturn(60);
        when(passwordResetService.available()).thenReturn(true);
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();

        mockMvc.perform(get("/api/auth/register-options").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.selfServiceEnabled").value(false))
            .andExpect(jsonPath("$.data.passwordResetEnabled").value(true));
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

        @Bean
        ClientIpResolver clientIpResolver(RegistrationGuardProperties properties) {
            return new ClientIpResolver(properties);
        }

        @Bean
        LoginCaptchaService loginCaptchaService() {
            return mock(LoginCaptchaService.class);
        }

        @Bean
        PasswordResetService passwordResetService() {
            return mock(PasswordResetService.class);
        }
    }
}
