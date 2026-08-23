package com.richard.fyoung.customerwork.safety.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 模型端点公网默认、私网白名单与 DNS rebinding 边界测试。 */
class ModelEndpointPolicyTest {

    @Test
    void publicEndpointShouldBeAllowedAndNormalized() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(List::of);

        assertEquals("https://8.8.8.8/v1",
            policy.validateAndNormalizeBaseUrl(" HTTPS://8.8.8.8/v1/ "));
    }

    @Test
    void privateLoopbackMetadataAndNonHttpShouldBeRejectedByDefault() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(List::of);

        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("http://10.0.0.8/v1"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("http://127.0.0.1:11434/v1"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("http://169.254.169.254/latest/meta-data"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("file:///etc/passwd"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("https://user:secret@8.8.8.8/v1"));
    }

    @Test
    void allowlistedEnterpriseHostMayResolveToPrivateNetwork() throws Exception {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(
            () -> List.of("model.internal"),
            host -> new InetAddress[] {InetAddress.getByName("10.20.30.40"), InetAddress.getByName("fd00::10")});

        assertDoesNotThrow(() -> policy.validateAndNormalizeBaseUrl("https://model.internal/v1"));
        assertEquals(2, policy.resolveForConnection("model.internal").size());
    }

    @Test
    void allowlistNeverPermitsLoopbackMetadataMulticastOrUnspecifiedAddress() {
        assertRejectedAddress("127.0.0.1");
        assertRejectedAddress("169.254.169.254");
        assertRejectedAddress("224.0.0.1");
        assertRejectedAddress("0.0.0.0");
    }

    @Test
    void nonAllowlistedHostShouldBeRejectedEvenWhenItResolvesPublicly() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(
            () -> List.of("model.internal"),
            host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});

        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("https://other.example/v1"));
    }

    @Test
    void connectionResolutionShouldRecheckDnsAndRejectRebinding() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        ModelEndpointPolicy policy = new ModelEndpointPolicy(
            () -> List.of("model.internal"),
            host -> resolutions.getAndIncrement() == 0
                ? new InetAddress[] {InetAddress.getByName("10.20.30.40")}
                : new InetAddress[] {InetAddress.getByName("127.0.0.1")});

        assertDoesNotThrow(() -> policy.validateAndNormalizeBaseUrl("https://model.internal/v1"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.resolveForConnection("model.internal"));
    }

    @Test
    void queryAndFragmentShouldNotBeAcceptedAsModelBaseUrl() {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(List::of);

        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("https://8.8.8.8/v1?target=x"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("https://8.8.8.8/v1#fragment"));
    }

    private void assertRejectedAddress(String address) {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(
            () -> List.of("model.internal"),
            host -> new InetAddress[] {InetAddress.getByName(address)});

        assertThrows(HttpTargetForbiddenException.class,
            () -> policy.validateAndNormalizeBaseUrl("https://model.internal/v1"));
    }
}
