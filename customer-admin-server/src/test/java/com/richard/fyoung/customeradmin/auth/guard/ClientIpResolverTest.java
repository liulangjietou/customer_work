package com.richard.fyoung.customeradmin.auth.guard;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 来源 IP 解析。
 *
 * <p>限流与登录锁定都按这个值分桶，解析错了要么全体共用一个桶（限得过严），
 * 要么每个伪造请求头各占一个桶（等于没限）。</p>
 */
class ClientIpResolverTest {

    @Test
    void resolve_shouldTakeLeftmostForwardedAddressWhenTrusted() {
        HttpServletRequest request = request("203.0.113.5, 70.41.3.18, 150.172.238.178", null, "10.0.0.1");

        assertEquals("203.0.113.5", ClientIpResolver.resolve(request, true));
    }

    @Test
    void resolve_shouldIgnoreForwardedHeaderWhenNotTrusted() {
        HttpServletRequest request = request("203.0.113.5", "198.51.100.7", "10.0.0.1");

        assertEquals("10.0.0.1", ClientIpResolver.resolve(request, false));
    }

    @Test
    void resolve_shouldFallBackToRealIpThenRemoteAddress() {
        assertEquals("198.51.100.7",
            ClientIpResolver.resolve(request(null, "198.51.100.7", "10.0.0.1"), true));
        assertEquals("10.0.0.1",
            ClientIpResolver.resolve(request(null, null, "10.0.0.1"), true));
    }

    /** 代理链路里 unknown 是常见占位值，当成拿不到处理而不是当成一个 IP。 */
    @Test
    void resolve_shouldTreatUnknownPlaceholderAsMissing() {
        assertEquals("10.0.0.1",
            ClientIpResolver.resolve(request("unknown", null, "10.0.0.1"), true));
        assertEquals("10.0.0.1",
            ClientIpResolver.resolve(request("  ", "UNKNOWN", "10.0.0.1"), true));
    }

    /** 全都取不到时返回固定串：限流退化为"全体共用一个桶"，而不是放行。 */
    @Test
    void resolve_shouldReturnStableFallbackInsteadOfNull() {
        assertEquals("unknown", ClientIpResolver.resolve(request(null, null, null), true));
        assertEquals("unknown", ClientIpResolver.resolve(null, true));
    }

    private HttpServletRequest request(String forwardedFor, String realIp, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(request.getHeader("X-Real-IP")).thenReturn(realIp);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }
}
