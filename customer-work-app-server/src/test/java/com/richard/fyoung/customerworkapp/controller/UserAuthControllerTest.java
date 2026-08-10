package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.user.UserAccount;
import com.richard.fyoung.customerwork.data.user.UserAccountService;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerworkapp.service.AvatarStorageService;
import com.richard.fyoung.customerworkapp.service.DemoOrderSeeder;
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
import static org.mockito.ArgumentMatchers.eq;
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
    void login_wrongPassword_shouldReturn401() {
        when(userAccountService.verifyLogin(any(), any())).thenReturn(Optional.empty());

        webTestClient.post().uri("/api/customer/auth/login")
            .bodyValue(Map.of("username", "alice", "password", "wrong"))
            .exchange()
            .expectStatus().isUnauthorized();
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
