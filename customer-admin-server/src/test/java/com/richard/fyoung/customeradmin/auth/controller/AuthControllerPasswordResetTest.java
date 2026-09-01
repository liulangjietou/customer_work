package com.richard.fyoung.customeradmin.auth.controller;

import com.richard.fyoung.customeradmin.auth.dto.PasswordResetEmailCodeRequest;
import com.richard.fyoung.customeradmin.auth.guard.ClientIpResolver;
import com.richard.fyoung.customeradmin.auth.guard.LoginCaptchaService;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.auth.service.PasswordResetService;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.config.SaTokenConfig;
import com.richard.fyoung.customeradmin.system.user.service.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 找回密码两个匿名接口的放行与请求契约。
 *
 * <p><b>这两条路径必须免登</b>——它们服务的正是进不去的人。漏在 {@code SaTokenConfig} 的放行清单外
 * 不会有任何编译或启动错误，只会让点「忘记密码」的用户撞上 401，而那时他已经没有别的出路了。</p>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = AuthControllerPasswordResetTest.WebMvcTestConfig.class)
class AuthControllerPasswordResetTest {

    private static final String CLIENT_IP = "203.0.113.7";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private PasswordResetService passwordResetService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(passwordResetService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void sendEmailCode_shouldBeAnonymousAndForwardTheResolvedClientIp() throws Exception {
        when(passwordResetService.sendCode(any(), anyString())).thenReturn(600);

        mockMvc.perform(post("/api/auth/password-reset/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", CLIENT_IP + ", 10.0.0.1")
                .content("""
                    {"username":"richard","email":"richard@example.com",
                     "captchaId":"cid-1","captcha":"AB12"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").value(600));

        ArgumentCaptor<PasswordResetEmailCodeRequest> request =
            ArgumentCaptor.forClass(PasswordResetEmailCodeRequest.class);
        ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
        verify(passwordResetService).sendCode(request.capture(), ip.capture());
        assertEquals("richard", request.getValue().username());
        assertEquals("richard@example.com", request.getValue().email());
        assertEquals("AB12", request.getValue().captcha());
        // 转发头默认不受信任，来源 IP 必须由 ClientIpResolver 统一裁定，而不是照抄 XFF 最左段
        assertEquals("127.0.0.1", ip.getValue());
    }

    @Test
    void resetPassword_shouldBeAnonymousAndAcceptTheDocumentedPayload() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"richard","email":"richard@example.com","emailCode":"123456",
                     "newPassword":"Reset2026pwd","confirmPassword":"Reset2026pwd"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(passwordResetService).reset(any(), anyString());
    }

    /**
     * 图形验证码在契约层就是必填，不是「服务端看情况要不要」。
     *
     * <p>发信是唯一会向站外第三方产生副作用的匿名操作；缺了图形码的请求根本不该走到 Service，
     * 更不该让服务端替调用者发出一封信。</p>
     */
    @Test
    void sendEmailCode_shouldRejectRequestWithoutCaptchaBeforeReachingService() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"richard","email":"richard@example.com"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(30001));

        verify(passwordResetService, never()).sendCode(any(), anyString());
    }

    @Test
    void resetPassword_shouldRejectMalformedEmailBeforeReachingService() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"richard","email":"not-an-email","emailCode":"123456",
                     "newPassword":"Reset2026pwd","confirmPassword":"Reset2026pwd"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(30001));

        verify(passwordResetService, never()).reset(any(), anyString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({AuthController.class, SaTokenConfig.class, GlobalExceptionHandler.class})
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
