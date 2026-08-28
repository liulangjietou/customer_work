package com.richard.fyoung.customeradmin.auth.guard;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 来源 IP 解析必须先证明直接连接方可信，再解析代理链。 */
class ClientIpResolverTest {

    @Test
    void registrationProperties_shouldDistrustForwardedHeadersByDefault() {
        assertFalse(new RegistrationGuardProperties().isTrustForwardedHeader());
    }

    @Test
    void resolve_shouldIgnoreForgedHeadersFromUntrustedImmediatePeer() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");
        HttpServletRequest request = request("203.0.113.5", "198.51.100.7", "192.0.2.44");

        assertEquals("192.0.2.44", resolver.resolve(request));
    }

    @Test
    void resolve_shouldUseSingleForwardedClientFromTrustedProxy() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");

        assertEquals("203.0.113.5",
            resolver.resolve(request("203.0.113.5", null, "10.0.0.7")));
        assertEquals("198.51.100.7",
            resolver.resolve(request(null, "198.51.100.7", "10.0.0.7")));
    }

    @Test
    void resolve_shouldIgnoreSpoofedLeftmostAddressInAppendMode() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");

        assertEquals("198.51.100.9",
            resolver.resolve(request("203.0.113.99, 198.51.100.9", null, "10.0.0.7")));
    }

    @Test
    void resolve_shouldCombineRepeatedForwardedHeadersBeforeStrippingTrustedChain() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");

        assertEquals("198.51.100.9", resolver.resolve(request(
            List.of("203.0.113.99", "198.51.100.9"), List.of(), "10.0.0.7")));
    }

    @Test
    void resolve_shouldStripMultipleTrustedProxiesFromRight() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8", "fd00::/8");

        assertEquals("198.51.100.9", resolver.resolve(request(
            "203.0.113.99, 198.51.100.9, fd00::12", null, "10.0.0.7")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "unknown",
        "client.example.com",
        "198.51.100.9:8080",
        "198.51.100.9,,10.0.0.8",
        "999.51.100.9"
    })
    void resolve_shouldFallBackToRemoteAddressWhenForwardedChainIsMalformed(String forwardedFor) {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");

        assertEquals("10.0.0.7", resolver.resolve(request(forwardedFor, null, "10.0.0.7")));
    }

    @Test
    void resolve_shouldRejectOversizedAndExcessiveForwardedChains() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");
        String tooManyHops = IntStream.range(0, 17)
            .mapToObj(index -> "198.51.100." + index)
            .collect(Collectors.joining(","));

        assertEquals("10.0.0.7",
            resolver.resolve(request("1".repeat(2_049), null, "10.0.0.7")));
        assertEquals("10.0.0.7",
            resolver.resolve(request(tooManyHops, null, "10.0.0.7")));
        assertEquals("10.0.0.7", resolver.resolve(request(
            List.of(" ".repeat(1_020) + "198.51.100.8", " ".repeat(1_020) + "198.51.100.9"),
            List.of(), "10.0.0.7")));
        assertEquals("10.0.0.7", resolver.resolve(request(
            List.of(
                IntStream.range(0, 8).mapToObj(index -> "198.51.100." + index)
                    .collect(Collectors.joining(",")),
                IntStream.range(8, 17).mapToObj(index -> "198.51.100." + index)
                    .collect(Collectors.joining(","))),
            List.of(), "10.0.0.7")));
    }

    @Test
    void resolve_shouldRejectRepeatedRealIpHeaders() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");

        assertEquals("10.0.0.7", resolver.resolve(request(
            List.of(), List.of("198.51.100.8", "198.51.100.9"), "10.0.0.7")));
    }

    @Test
    void resolve_shouldFallBackWhenEntireForwardedChainIsTrusted() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/8");

        assertEquals("10.0.0.7",
            resolver.resolve(request("10.1.1.1, 10.2.2.2", null, "10.0.0.7")));
        assertEquals("10.0.0.7",
            resolver.resolve(request(null, "10.3.3.3", "10.0.0.7")));
    }

    @Test
    void resolve_shouldCanonicalizeIpv6AndIpv4MappedAddresses() {
        ClientIpResolver ipv6Resolver = resolver(true, "2001:db8:1::/48");
        assertEquals("2001:db8:2:0:0:0:0:9", ipv6Resolver.resolve(
            request("2001:0db8:0002:0000:0000:0000:0000:0009", null, "2001:db8:1::7")));

        ClientIpResolver mappedResolver = resolver(true, "10.0.0.0/8");
        assertEquals("198.51.100.9", mappedResolver.resolve(
            request("198.51.100.9", null, "::ffff:10.0.0.7")));
    }

    @Test
    void resolve_shouldHonorCidrBoundary() {
        ClientIpResolver resolver = resolver(true, "10.0.0.0/25");

        assertEquals("203.0.113.5",
            resolver.resolve(request("203.0.113.5", null, "10.0.0.127")));
        assertEquals("10.0.0.128",
            resolver.resolve(request("203.0.113.5", null, "10.0.0.128")));

        ClientIpResolver ipv6Resolver = resolver(true, "2001:db8::/65");
        assertEquals("198.51.100.9", ipv6Resolver.resolve(
            request("198.51.100.9", null, "2001:db8:0:0:7fff::1")));
        assertEquals("2001:db8:0:0:8000:0:0:1", ipv6Resolver.resolve(
            request("198.51.100.9", null, "2001:db8:0:0:8000::1")));
    }

    @Test
    void constructor_shouldFailFastForUnsafeOrInvalidProxyConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> new ClientIpResolver(true, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientIpResolver(true, List.of("10.0.0.0/33")));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientIpResolver(true, List.of("0.0.0.0/0")));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientIpResolver(true, List.of("::/0")));
        assertThrows(IllegalArgumentException.class,
            () -> new ClientIpResolver(false, List.of("proxy.example.com/24")));
    }

    @Test
    void resolve_shouldReturnStableUnknownBucketForMissingOrInvalidRemoteAddress() {
        ClientIpResolver resolver = new ClientIpResolver(false, List.of());

        assertEquals("unknown", resolver.resolve(null));
        assertEquals("unknown", resolver.resolve(request("203.0.113.5", null, null)));
        assertEquals("unknown", resolver.resolve(request("203.0.113.5", null, "localhost")));
    }

    private ClientIpResolver resolver(boolean trustForwardedHeader, String... cidrs) {
        return new ClientIpResolver(trustForwardedHeader, List.of(cidrs));
    }

    private HttpServletRequest request(String forwardedFor, String realIp, String remoteAddr) {
        return request(forwardedFor == null ? List.of() : List.of(forwardedFor),
            realIp == null ? List.of() : List.of(realIp), remoteAddr);
    }

    private HttpServletRequest request(List<String> forwardedFor, List<String> realIp, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaders("X-Forwarded-For"))
            .thenReturn(Collections.enumeration(forwardedFor));
        when(request.getHeaders("X-Real-IP"))
            .thenReturn(Collections.enumeration(realIp));
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }
}
