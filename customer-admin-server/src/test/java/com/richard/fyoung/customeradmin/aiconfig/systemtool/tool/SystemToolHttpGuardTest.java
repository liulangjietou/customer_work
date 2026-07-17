package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemToolHttpGuard} 单测：只测校验逻辑，不发真实外网请求。
 *
 * <p>默认模式的内网/环回拒绝用 IP 字面量（{@code InetAddress.getAllByName} 对 IP 字面量不走 DNS，
 * 天然离线可测）；白名单模式只做 host 字符串匹配，同样不触网。</p>
 * @author owlzhangfq@gmail.com
 */
class SystemToolHttpGuardTest {

    private SystemToolHttpGuard guard(String... allowedHosts) {
        AdminSystemToolProperties properties = new AdminSystemToolProperties();
        if (allowedHosts.length > 0) {
            properties.getHttp().setAllowedHosts(List.of(allowedHosts));
        }
        return new SystemToolHttpGuard(properties);
    }

    // ---------- 默认模式：拒内网/环回 ----------

    @Test
    void defaultMode_shouldRejectPrivateIp() {
        BizException ex = assertThrows(BizException.class, () -> guard().checkAllowed("http://10.1.2.3/api"));
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, ex.getResultCode());
    }

    @Test
    void defaultMode_shouldRejectPrivateIp_192168() {
        assertThrows(BizException.class, () -> guard().checkAllowed("http://192.168.0.10:8080/x"));
    }

    @Test
    void defaultMode_shouldRejectPrivateIp_17216() {
        assertThrows(BizException.class, () -> guard().checkAllowed("http://172.16.5.5/x"));
    }

    @Test
    void defaultMode_shouldRejectLoopback() {
        assertThrows(BizException.class, () -> guard().checkAllowed("http://127.0.0.1:9000/x"));
    }

    @Test
    void defaultMode_shouldRejectLinkLocal() {
        assertThrows(BizException.class, () -> guard().checkAllowed("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void defaultMode_shouldRejectIpv6Loopback() {
        assertThrows(BizException.class, () -> guard().checkAllowed("http://[::1]:8080/x"));
    }

    @Test
    void defaultMode_shouldRejectBlankUrl() {
        assertThrows(BizException.class, () -> guard().checkAllowed("  "));
    }

    @Test
    void defaultMode_shouldRejectUrlWithoutHost() {
        assertThrows(BizException.class, () -> guard().checkAllowed("not-a-valid-url"));
    }

    @Test
    void defaultMode_shouldAllowPublicIpLiteral() {
        // 8.8.8.8 是公网字面量，getAllByName 不触网即可解析；不应被拦截。
        assertDoesNotThrow(() -> guard().checkAllowed("http://8.8.8.8/x"));
    }

    // ---------- 白名单模式 ----------

    @Test
    void whitelistMode_shouldAllowExactHost() {
        assertDoesNotThrow(() -> guard("api.example.com").checkAllowed("https://api.example.com/v1/x"));
    }

    @Test
    void whitelistMode_shouldAllowWildcardSubdomain() {
        assertDoesNotThrow(() -> guard("*.example.com").checkAllowed("https://svc.internal.example.com/x"));
    }

    @Test
    void whitelistMode_shouldRejectNonWhitelistedPublicHost() {
        BizException ex = assertThrows(BizException.class,
            () -> guard("api.example.com").checkAllowed("https://evil.attacker.com/x"));
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, ex.getResultCode());
    }

    @Test
    void whitelistMode_wildcardShouldNotMatchDifferentDomain() {
        assertThrows(BizException.class,
            () -> guard("*.example.com").checkAllowed("https://example.com.evil.com/x"));
    }

    // ---------- 纯方法：host 匹配 / IP 判定 ----------

    @Test
    void hostWhitelisted_shouldBeCaseInsensitive() {
        assertTrue(guard().hostWhitelisted("API.Example.COM", List.of("api.example.com")));
    }

    @Test
    void isInternalAddress_classification() throws Exception {
        SystemToolHttpGuard g = guard();
        assertTrue(g.isInternalAddress(InetAddress.getByName("10.0.0.1")));
        assertTrue(g.isInternalAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(g.isInternalAddress(InetAddress.getByName("169.254.1.1")));
        assertFalse(g.isInternalAddress(InetAddress.getByName("8.8.8.8")));
    }
}
