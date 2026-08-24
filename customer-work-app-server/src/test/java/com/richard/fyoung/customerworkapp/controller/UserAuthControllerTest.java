package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.user.UserAccount;
import com.richard.fyoung.customerwork.data.user.UserAccountService;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.service.AvatarStorageService;
import com.richard.fyoung.customerworkapp.service.DemoOrderSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户账户端点切片测试：注册、重名 409、登录、密码错 401、/me。
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(UserAuthController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class})
class UserAuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserJwtService jwtService;

    @MockBean
    private UserAccountService userAccountService;

    @MockBean
    private DemoOrderSeeder demoOrderSeeder;

    @MockBean
    private AvatarStorageService avatarStorageService;

    @MockBean
    private TenantAccessGuard tenantAccessGuard;

    @BeforeEach
    void allowTenantAccessByDefault() {
        when(tenantAccessGuard.refreshAndCheck(anyString(), nullable(Long.class), anyBoolean()))
            .thenReturn(TenantAccessDecision.allowed(0L));
        when(tenantAccessGuard.check(anyString(), nullable(Long.class), anyBoolean()))
            .thenReturn(TenantAccessDecision.allowed(0L));
        when(userAccountService.isSessionActive(anyString(), anyString(), nullable(Long.class)))
            .thenReturn(true);
    }

    private UserAccount account() {
        return UserAccount.create("U-1", "alice", "hash", "Alice", "13800000000");
    }

    @Test
    void register_shouldReturnUserId() {
        when(userAccountService.register(eq("alice"), any(), any(), any())).thenReturn(account());

        webTestClient.post().uri("/api/customer/auth/register")
            .bodyValue(Map.of("username", "alice", "password", "secret1", "nickname", "Alice"))
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.userId").isEqualTo("U-1");
    }

    @Test
    void register_duplicateUsername_shouldReturn409() {
        when(userAccountService.register(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("username already exists: alice"));

        webTestClient.post().uri("/api/customer/auth/register")
            .bodyValue(Map.of("username", "alice", "password", "secret1"))
            .exchange()
            .expectStatus().isEqualTo(409);
    }

    @Test
    void register_shortUsername_shouldReturn400() {
        webTestClient.post().uri("/api/customer/auth/register")
            .bodyValue(Map.of("username", "ab", "password", "secret1"))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void register_withInvalidTenantCode_shouldReturn400WithoutWritingUser() {
        webTestClient.post().uri("/api/customer/auth/register")
            .bodyValue(Map.of("username", "alice", "password", "secret1",
                "tenantCode", "__platform__"))
            .exchange()
            .expectStatus().isBadRequest();

        verify(userAccountService, never()).register(any(), any(), any(), any());
    }

    @Test
    void login_shouldReturnToken() {
        when(userAccountService.verifyLogin("alice", "secret1")).thenReturn(Optional.of(account()));

        webTestClient.post().uri("/api/customer/auth/login")
            .bodyValue(Map.of("username", "alice", "password", "secret1"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.userId").isEqualTo("U-1")
            .jsonPath("$.nickname").isEqualTo("Alice")
            .jsonPath("$.token").isNotEmpty()
            .jsonPath("$.expiresAtMs").isNumber();
    }

    @Test
    void login_shouldIssueTokenWithStoredAuthoritativeTenantCode() {
        UserAccount stored = TenantContext.callWith("AcMe", this::account);
        when(userAccountService.verifyLogin("alice", "secret1")).thenReturn(Optional.of(stored));

        webTestClient.post().uri("/api/customer/auth/login")
            .bodyValue(Map.of("username", "alice", "password", "secret1", "tenantCode", "acme"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.token").value(token -> assertEquals("AcMe",
                jwtService.verify(String.valueOf(token)).orElseThrow().tenantId()));
    }

    @Test
    void login_wrongPassword_shouldReturn401() {
        when(userAccountService.verifyLogin(any(), any())).thenReturn(Optional.empty());

        webTestClient.post().uri("/api/customer/auth/login")
            .bodyValue(Map.of("username", "alice", "password", "wrong"))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void login_withInvalidTenantCode_shouldReturn400WithoutReadingUser() {
        webTestClient.post().uri("/api/customer/auth/login")
            .bodyValue(Map.of("username", "alice", "password", "secret1",
                "tenantCode", "__platform__"))
            .exchange()
            .expectStatus().isBadRequest();

        verify(userAccountService, never()).verifyLogin(any(), any());
    }

    @Test
    void login_frozenTenantShouldFailBeforeReadingCredentials() {
        when(tenantAccessGuard.refreshAndCheck(eq("acme"), isNull(), eq(false))).thenReturn(
            new TenantAccessDecision(TenantAccessDecision.Kind.ACCESS_DENIED, 7L));

        webTestClient.post().uri("/api/customer/auth/login")
            .bodyValue(Map.of("username", "alice", "password", "secret1", "tenantCode", "acme"))
            .exchange()
            .expectStatus().isForbidden();

        verify(userAccountService, never()).verifyLogin(any(), any());
    }

    @Test
    void me_withValidToken_shouldReturnProfile() {
        when(userAccountService.findById("U-1")).thenReturn(Optional.of(account()));
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.get().uri("/api/customer/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.userId").isEqualTo("U-1")
            .jsonPath("$.phone").isEqualTo("13800000000");
    }

    @Test
    void me_withoutToken_shouldReturn401() {
        webTestClient.get().uri("/api/customer/auth/me")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void me_withLegacyTenantToken_shouldReturn401() {
        String token = jwtService.issue("U-1", "alice", "Alice", "__platform__");

        webTestClient.get().uri("/api/customer/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isUnauthorized();

        verify(userAccountService, never()).findById(any());
    }

    @Test
    void me_withRevokedUserSession_shouldReturn401() {
        when(userAccountService.isSessionActive(eq(TenantContext.DEFAULT), eq("U-1"), eq(0L)))
            .thenReturn(false);
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.get().uri("/api/customer/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isUnauthorized();

        verify(userAccountService, never()).findById(any());
    }

    @Test
    void revokeSessions_withValidToken_shouldReturnNewEpoch() {
        when(userAccountService.revokeSessions("U-1")).thenAnswer(invocation -> {
            assertEquals(TenantContext.DEFAULT, TenantContext.get());
            return 1L;
        });
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.post().uri("/api/customer/auth/revoke-sessions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.revoked").isEqualTo(true)
            .jsonPath("$.sessionEpoch").isEqualTo(1);

        verify(userAccountService).revokeSessions("U-1");
    }

    @Test
    void me_shouldExposeAvatarUrl() {
        UserAccount withAvatar = account();
        withAvatar.changeAvatar("/api/avatars/pic.png");
        when(userAccountService.findById("U-1")).thenReturn(Optional.of(withAvatar));
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.get().uri("/api/customer/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.avatarUrl").isEqualTo("/api/avatars/pic.png");
    }

    @Test
    void uploadAvatar_withValidToken_shouldReturnUrlAndPersist() {
        when(avatarStorageService.store(any())).thenReturn(Mono.just("/api/avatars/new.png"));
        when(userAccountService.updateAvatar(eq("U-1"), eq("/api/avatars/new.png"))).thenReturn(account());
        String token = jwtService.issue("U-1", "alice", "Alice");

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource("fake-image-bytes".getBytes())).filename("avatar.png");

        webTestClient.post().uri("/api/customer/auth/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.avatarUrl").isEqualTo("/api/avatars/new.png");

        verify(userAccountService).updateAvatar("U-1", "/api/avatars/new.png");
    }

    @Test
    void uploadAvatar_withoutToken_shouldReturn401() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource("fake-image-bytes".getBytes())).filename("avatar.png");

        webTestClient.post().uri("/api/customer/auth/avatar")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
