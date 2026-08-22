package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.memory.MemoryConsent;
import com.richard.fyoung.customerwork.core.memory.MemoryConsentService;
import com.richard.fyoung.customerwork.core.memory.MemoryConsentStatus;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectKey;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectType;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用户长期记忆隐私端点切片测试：主体取自 JWT，覆盖同意、查看、删除与未认证路径。 */
@WebFluxTest(UserPrivacyController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class,
    ControllerSecurityTestConfiguration.UserAuth.class})
class UserPrivacyControllerTest {

    private static final String USER_ID = "U1";
    private static final String TENANT_ID = "tenant-a";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserJwtService jwtService;

    @MockBean
    private MemorySubjectResolver subjectResolver;

    @MockBean
    private MemoryConsentService consentService;

    private MemorySubjectKey subject;

    @BeforeEach
    void setUp() {
        subject = new MemorySubjectKey(TENANT_ID, MemorySubjectType.USER, USER_ID,
            MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);
        when(subjectResolver.user(TENANT_ID, USER_ID)).thenReturn(subject);
    }

    @Test
    void consent_shouldReturnStatusForVerifiedJwtSubject() {
        when(consentService.status(subject)).thenReturn(new MemoryConsent(subject,
            MemoryConsentStatus.WITHDRAWN, "privacy-v1", 10L, 20L, 20L));

        webTestClient.get().uri("/api/customer/user/privacy/memory-consent")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.granted").isEqualTo(false)
            .jsonPath("$.consentVersion").isEqualTo("privacy-v1")
            .jsonPath("$.withdrawnAtMs").isEqualTo(20);

        verify(subjectResolver).user(TENANT_ID, USER_ID);
    }

    @Test
    void updateConsent_shouldGrantRequestedVersion() {
        when(consentService.grant(subject, "privacy-2026-08")).thenReturn(new MemoryConsent(subject,
            MemoryConsentStatus.GRANTED, "privacy-2026-08", 30L, null, 30L));

        webTestClient.put().uri("/api/customer/user/privacy/memory-consent")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(new UserPrivacyController.MemoryConsentRequest(true, "privacy-2026-08"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.granted").isEqualTo(true)
            .jsonPath("$.consentVersion").isEqualTo("privacy-2026-08");

        verify(consentService).grant(subject, "privacy-2026-08");
    }

    @Test
    void memoriesAndClear_shouldOnlyUseVerifiedJwtSubject() {
        when(consentService.list(subject, 25)).thenReturn(List.of("偏好顺丰", "地址杭州"));
        when(consentService.listFacts(subject, 25)).thenReturn(List.of("明确要求人工客服"));

        webTestClient.get().uri("/api/customer/user/privacy/memory?limit=25")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.count").isEqualTo(3)
            .jsonPath("$.memories[0]").isEqualTo("偏好顺丰")
            .jsonPath("$.facts[0]").isEqualTo("明确要求人工客服");

        webTestClient.delete().uri("/api/customer/user/privacy/memory")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectBody().isEmpty();

        verify(consentService).withdraw(subject);
    }

    @Test
    void export_shouldReturnDownloadWithoutExposingRawSubjectId() {
        when(consentService.status(subject)).thenReturn(new MemoryConsent(subject,
            MemoryConsentStatus.GRANTED, "privacy-v1", 10L, null, 10L));
        when(consentService.list(subject, 200)).thenReturn(List.of("偏好顺丰"));
        when(consentService.listFacts(subject, 200)).thenReturn(List.of("明确要求人工客服"));

        webTestClient.get().uri("/api/customer/user/privacy/memory/export")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType("application/json")
            .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=memory-export.json")
            .expectBody()
            .jsonPath("$.schemaVersion").isEqualTo("memory-export-v1")
            .jsonPath("$.subjectType").isEqualTo("USER")
            .jsonPath("$.agentId").isEqualTo("customer-service")
            .jsonPath("$.consent.granted").isEqualTo(true)
            .jsonPath("$.memories[0]").isEqualTo("偏好顺丰")
            .jsonPath("$.facts[0]").isEqualTo("明确要求人工客服")
            .jsonPath("$.subjectId").doesNotExist();
    }

    @Test
    void updateConsent_withoutGranted_shouldReturn400() {
        webTestClient.put().uri("/api/customer/user/privacy/memory-consent")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(new UserPrivacyController.MemoryConsentRequest(null, "privacy-v1"))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void endpoint_withoutToken_shouldReturn401() {
        webTestClient.get().uri("/api/customer/user/privacy/memory")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(USER_ID, "alice", "Alice", TENANT_ID);
    }
}
