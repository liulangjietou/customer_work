package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.service.UserRefundApprovalQueryService;
import com.richard.fyoung.customerworkapp.service.UserSessionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 用户会话退款办理记录的 HTTP 契约；首个 RED 证明现有会话端点没有该 GET 路由。 */
@WebFluxTest(UserRefundApprovalController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class,
    ControllerSecurityTestConfiguration.UserAuth.class})
class UserRefundApprovalControllerTest {
    @Autowired private WebTestClient client;
    @Autowired private UserJwtService jwtService;
    @MockBean private UserRefundApprovalQueryService queryService;
    @MockBean private UserSessionGuard sessionGuard;

    @Test
    void ownedSessionReturnsFlatRefundApprovalPage() {
        when(sessionGuard.requireOwned("session-A", "user-A"))
            .thenReturn(Ticket.create("ticket-A", "session-A", "user-A", "咨询", TicketCategory.CONSULT));
        when(queryService.findPage("session-A", "user-A", 1, 10)).thenAnswer(invocation -> {
            assertEquals("tenant-A", TenantContext.require());
            return new PageResult<>(0, List.of());
        });
        client.get().uri("/api/customer/user/sessions/session-A/refund-approvals")
            .header(HttpHeaders.AUTHORIZATION, bearer()).header("X-Tenant-Id", "tenant-B")
            .exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody()
            .jsonPath("$.total").isEqualTo(0)
            .jsonPath("$.items").isArray();
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0", "page=-1", "size=0", "size=-1", "size=51", "page=abc"})
    void invalidPaginationIsRejectedBeforeReading(String query) {
        client.get().uri("/api/customer/user/sessions/session-A/refund-approvals?" + query)
            .header(HttpHeaders.AUTHORIZATION, bearer()).exchange().expectStatus().isBadRequest();
        verifyNoInteractions(sessionGuard, queryService);
    }

    @Test
    void missingJwtIsRejectedBeforeReading() {
        client.get().uri("/api/customer/user/sessions/session-A/refund-approvals")
            .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(sessionGuard, queryService);
    }

    @Test
    void sessionGuardFailureDoesNotReadApprovals() {
        when(sessionGuard.requireOwned("session-A", "user-A"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        client.get().uri("/api/customer/user/sessions/session-A/refund-approvals")
            .header(HttpHeaders.AUTHORIZATION, bearer()).exchange().expectStatus().isNotFound();
        verifyNoInteractions(queryService);
    }

    @Test
    void caseInsensitiveRootLookupCannotAuthorizeDifferentSessionSpelling() {
        when(sessionGuard.requireOwned("session-A", "user-A"))
            .thenReturn(Ticket.create("ticket-A", "SESSION-A", "user-A", "咨询", TicketCategory.CONSULT));
        client.get().uri("/api/customer/user/sessions/session-A/refund-approvals")
            .header(HttpHeaders.AUTHORIZATION, bearer()).exchange().expectStatus().isNotFound();
        verifyNoInteractions(queryService);
    }

    private String bearer() {
        return "Bearer " + jwtService.issue("user-A", "alice", "Alice", "tenant-A");
    }
}
