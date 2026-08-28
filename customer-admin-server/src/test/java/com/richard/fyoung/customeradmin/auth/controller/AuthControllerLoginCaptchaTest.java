package com.richard.fyoung.customeradmin.auth.controller;

import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaChallengeResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaProofResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginResponse;
import com.richard.fyoung.customeradmin.auth.guard.ClientIpResolver;
import com.richard.fyoung.customeradmin.auth.guard.LoginCaptchaService;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.config.SaTokenConfig;
import com.richard.fyoung.customeradmin.system.user.service.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 登录滑块匿名接口、请求契约及同一来源上下文传递。 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = AuthControllerLoginCaptchaTest.WebMvcTestConfig.class)
class AuthControllerLoginCaptchaTest {

    private static final String CLIENT_IP = "203.0.113.7";
    private static final String USER_AGENT = "Mozilla/5.0 Contract";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private LoginCaptchaService loginCaptchaService;

    @Autowired
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(loginCaptchaService, authService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void challenge_shouldBeAnonymousAndExposeStableContract() throws Exception {
        when(loginCaptchaService.issueChallenge(CLIENT_IP, USER_AGENT))
            .thenReturn(new LoginCaptchaChallengeResponse("challenge-id", 120));

        mockMvc.perform(post("/api/auth/login-captcha/challenge")
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", CLIENT_IP + ", 10.0.0.1")
                .header("User-Agent", USER_AGENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.challengeId").value("challenge-id"))
            .andExpect(jsonPath("$.data.ttlSeconds").value(120));

        verify(loginCaptchaService).issueChallenge(CLIENT_IP, USER_AGENT);
    }

    @Test
    void challenge_shouldKeepSameSourceForChangingForgedHeadersFromUntrustedPeer() throws Exception {
        String directPeer = "192.0.2.44";
        when(loginCaptchaService.issueChallenge(directPeer, USER_AGENT))
            .thenReturn(new LoginCaptchaChallengeResponse("challenge-id", 120));

        for (String forgedIp : java.util.List.of(CLIENT_IP, "198.51.100.99")) {
            mockMvc.perform(post("/api/auth/login-captcha/challenge")
                    .with(request -> {
                        request.setRemoteAddr(directPeer);
                        return request;
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Forwarded-For", forgedIp)
                    .header("User-Agent", USER_AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        verify(loginCaptchaService, times(2)).issueChallenge(directPeer, USER_AGENT);
    }

    @Test
    void verify_shouldBeAnonymousAndPassNormalizedTrajectoryContract() throws Exception {
        when(loginCaptchaService.verify(any(), eq(CLIENT_IP), eq(USER_AGENT)))
            .thenReturn(new LoginCaptchaProofResponse("opaque-proof", 120));

        mockMvc.perform(post("/api/auth/login-captcha/verify")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", CLIENT_IP)
                .header("User-Agent", USER_AGENT)
                .content("""
                    {"challengeId":"challenge-id","trajectory":[
                      {"x":0,"y":0,"t":0},{"x":120,"y":1,"t":60},
                      {"x":320,"y":-1,"t":120},{"x":560,"y":2,"t":190},
                      {"x":800,"y":0,"t":260},{"x":1000,"y":1,"t":340}
                    ]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.proof").value("opaque-proof"))
            .andExpect(jsonPath("$.data.ttlSeconds").value(120));

        verify(loginCaptchaService).verify(any(), eq(CLIENT_IP), eq(USER_AGENT));
    }

    @Test
    void verify_outOfRangePoint_shouldFailBeforeService() throws Exception {
        mockMvc.perform(post("/api/auth/login-captcha/verify")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", USER_AGENT)
                .content("""
                    {"challengeId":"challenge-id","trajectory":[
                      {"x":0,"y":0,"t":0},{"x":120,"y":1,"t":60},
                      {"x":320,"y":-1,"t":120},{"x":560,"y":2,"t":190},
                      {"x":800,"y":0,"t":260},{"x":1001,"y":1,"t":340}
                    ]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(30001))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("trajectory[5].x")));

        verify(loginCaptchaService, never()).verify(any(), any(), any());
    }

    @Test
    void localAndSsoLogin_shouldRequireProofAndReuseRequestContext() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"richard\",\"password\":\"password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(30001))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("captchaProof")));
        verify(authService, never()).login(any(), any(), any());

        when(authService.login(any(), eq(CLIENT_IP), eq(USER_AGENT)))
            .thenReturn(new LoginResponse("token", "Richard", false, "APPROVED", null));
        mockMvc.perform(post("/api/auth/login")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", CLIENT_IP)
                .header("User-Agent", USER_AGENT)
                .content("""
                    {"username":"richard","password":"password",
                     "rememberMe":false,"captchaProof":"proof"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
        verify(authService).login(any(), eq(CLIENT_IP), eq(USER_AGENT));

        when(authService.ssoLogin(any(), eq(CLIENT_IP), eq(USER_AGENT)))
            .thenReturn(new LoginResponse("token", "Richard", false, "APPROVED", null));
        mockMvc.perform(post("/api/auth/sso-login")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", CLIENT_IP)
                .header("User-Agent", USER_AGENT)
                .content("""
                    {"username":"richard","password":"password",
                     "rememberMe":false,"captchaProof":"proof"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
        verify(authService).ssoLogin(any(), eq(CLIENT_IP), eq(USER_AGENT));
    }

    @ParameterizedTest
    @MethodSource("oversizedLoginPayloads")
    void loginCredentialsBeyondContract_shouldFailBeforeService(String path, String payload) throws Exception {
        mockMvc.perform(post(path)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(30001));

        verify(authService, never()).login(any(), any(), any());
        verify(authService, never()).ssoLogin(any(), any(), any());
    }

    private static Stream<Arguments> oversizedLoginPayloads() {
        return Stream.of(
            Arguments.of("/api/auth/login", loginPayload("u".repeat(65), "password")),
            Arguments.of("/api/auth/login", loginPayload("richard", "p".repeat(65))),
            Arguments.of("/api/auth/login", loginPayload("richard", "password", "p".repeat(257))),
            Arguments.of("/api/auth/sso-login", loginPayload("u".repeat(257), "password")),
            Arguments.of("/api/auth/sso-login", loginPayload("richard", "p".repeat(257))),
            Arguments.of("/api/auth/sso-login", loginPayload("richard", "password", "p".repeat(257))));
    }

    private static String loginPayload(String username, String password) {
        return loginPayload(username, password, "proof");
    }

    private static String loginPayload(String username, String password, String captchaProof) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password
            + "\",\"rememberMe\":false,\"captchaProof\":\"" + captchaProof + "\"}";
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
            RegistrationGuardProperties properties = new RegistrationGuardProperties();
            properties.setTrustForwardedHeader(true);
            properties.setTrustedProxyCidrs(java.util.List.of("127.0.0.1/32", "10.0.0.0/8"));
            return properties;
        }

        @Bean
        ClientIpResolver clientIpResolver(RegistrationGuardProperties properties) {
            return new ClientIpResolver(properties);
        }

        @Bean
        LoginCaptchaService loginCaptchaService() {
            return mock(LoginCaptchaService.class);
        }
    }
}
