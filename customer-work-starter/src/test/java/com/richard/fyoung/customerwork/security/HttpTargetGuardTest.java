package com.richard.fyoung.customerwork.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpTargetGuard} 单测：只测校验逻辑，不发真实外网请求。
 *
 * <p>内网/环回判定用 IP 字面量（{@code InetAddress.getAllByName} 对 IP 字面量不走 DNS，天然离线可测）；
 * 白名单模式只做 host 字符串匹配，同样不触网。</p>
 * @author owlzhangfq@gmail.com
 */
class HttpTargetGuardTest {

    /** 模型可控地址的策略：默认拒内网。 */
    private HttpTargetGuard denyInternal(String... allowedHosts) {
        return new HttpTargetGuard(
            HttpTargetPolicy.of(List.of(allowedHosts), InternalAddressPolicy.DENY_INTERNAL));
    }

    /** 管理员配置地址的策略：默认放内网，只拦链路本地。 */
    private HttpTargetGuard allowInternal(String... allowedHosts) {
        return new HttpTargetGuard(
            HttpTargetPolicy.of(List.of(allowedHosts), InternalAddressPolicy.ALLOW_INTERNAL));
    }

    // ---------- 公共校验：URL / 协议 / host ----------

    @Test
    void shouldRejectBlankUrl() {
        assertThrows(HttpTargetForbiddenException.class, () -> denyInternal().checkAllowed("  "));
        assertThrows(HttpTargetForbiddenException.class, () -> allowInternal().checkAllowed(""));
    }

    @Test
    void shouldRejectNonHttpScheme() {
        assertThrows(HttpTargetForbiddenException.class, () -> denyInternal().checkAllowed("file:///etc/passwd"));
        assertThrows(HttpTargetForbiddenException.class, () -> allowInternal().checkAllowed("ftp://example.com"));
    }

    @Test
    void shouldRejectUrlWithoutHost() {
        // 无 scheme 的裸串在协议校验处即被拦；有 scheme 无 host 的走 host 校验
        assertThrows(HttpTargetForbiddenException.class, () -> denyInternal().checkAllowed("not-a-valid-url"));
        assertThrows(HttpTargetForbiddenException.class, () -> allowInternal().checkAllowed("http:///no-host"));
    }

    // ---------- DENY_INTERNAL：拒内网/环回 ----------

    @Test
    void denyInternal_shouldRejectPrivateIp() {
        assertThrows(HttpTargetForbiddenException.class, () -> denyInternal().checkAllowed("http://10.1.2.3/api"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> denyInternal().checkAllowed("http://192.168.0.10:8080/x"));
        assertThrows(HttpTargetForbiddenException.class, () -> denyInternal().checkAllowed("http://172.16.5.5/x"));
    }

    @Test
    void denyInternal_shouldRejectLoopbackAndLinkLocal() {
        assertThrows(HttpTargetForbiddenException.class, () -> denyInternal().checkAllowed("http://127.0.0.1:9000/x"));
        assertThrows(HttpTargetForbiddenException.class, () -> denyInternal().checkAllowed("http://[::1]:8080/x"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> denyInternal().checkAllowed("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void denyInternal_shouldAllowPublicIpLiteral() {
        // 8.8.8.8 是公网字面量，getAllByName 不触网即可解析；不应被拦截
        assertDoesNotThrow(() -> denyInternal().checkAllowed("http://8.8.8.8/x"));
    }

    // ---------- ALLOW_INTERNAL：放内网，只拦链路本地 ----------

    @Test
    void allowInternal_shouldPassLoopbackAndPrivateNetwork() {
        // 企业内部服务（RAG 等）本就部署在内网，默认策略必须放行，否则功能直接不可用
        assertDoesNotThrow(() -> allowInternal().checkAllowed("http://localhost:20002"));
        assertDoesNotThrow(() -> allowInternal().checkAllowed("http://127.0.0.1:20002/"));
        assertDoesNotThrow(() -> allowInternal().checkAllowed("http://192.168.1.10:8080"));
        assertDoesNotThrow(() -> allowInternal().checkAllowed("http://10.0.0.7"));
    }

    @Test
    void allowInternal_shouldRejectLinkLocalMetadataAddress() {
        // 169.254.169.254 是云元数据服务，绝无可能是业务服务，是该策略下唯一被拦的一类地址
        assertThrows(HttpTargetForbiddenException.class,
            () -> allowInternal().checkAllowed("http://169.254.169.254/latest/meta-data"));
    }

    // ---------- 白名单模式（两种策略共用同一套匹配） ----------

    @Test
    void whitelistMode_shouldAllowExactAndWildcardHost() {
        assertDoesNotThrow(() -> denyInternal("api.example.com").checkAllowed("https://api.example.com/v1/x"));
        assertDoesNotThrow(() -> denyInternal("*.example.com").checkAllowed("https://svc.internal.example.com/x"));
    }

    @Test
    void whitelistMode_shouldRejectNonWhitelistedHost() {
        assertThrows(HttpTargetForbiddenException.class,
            () -> denyInternal("api.example.com").checkAllowed("https://evil.attacker.com/x"));
        assertThrows(HttpTargetForbiddenException.class,
            () -> denyInternal("*.example.com").checkAllowed("https://example.com.evil.com/x"));
    }

    @Test
    void whitelistMode_shouldSkipInternalCheck_soLoopbackCanBeAllowed() {
        // 白名单本身即信任边界：显式放行 127.0.0.1 后，拒内网策略也不再对其做地址判定
        assertDoesNotThrow(() -> denyInternal("127.0.0.1").checkAllowed("http://127.0.0.1:8080/x"));
    }

    @Test
    void whitelistMode_shouldRejectHostOutsideWhitelist_underAllowInternalPolicy() {
        HttpTargetGuard guard = allowInternal("rag.internal.corp", "*.example.com");

        assertDoesNotThrow(() -> guard.checkAllowed("http://rag.internal.corp:20002"));
        assertDoesNotThrow(() -> guard.checkAllowed("https://kb.example.com/api"));
        assertThrows(HttpTargetForbiddenException.class, () -> guard.checkAllowed("http://localhost:20002"));
        assertThrows(HttpTargetForbiddenException.class, () -> guard.checkAllowed("https://evil.com"));
    }

    // ---------- 纯方法：host 匹配 / 地址分类 ----------

    @Test
    void hostWhitelisted_shouldMatchExactAndWildcardSuffix() {
        HttpTargetGuard guard = denyInternal();
        List<String> whitelist = List.of("rag.internal.corp", "*.example.com");

        assertTrue(guard.hostWhitelisted("RAG.INTERNAL.CORP", whitelist), "精确匹配应忽略大小写");
        assertTrue(guard.hostWhitelisted("a.b.example.com", whitelist), "通配后缀应匹配多级子域");
        assertFalse(guard.hostWhitelisted("example.com", whitelist), "*.example.com 不匹配裸域名");
        assertFalse(guard.hostWhitelisted("notexample.com", whitelist));
    }

    @Test
    void internalAddressPolicy_classification() throws Exception {
        assertTrue(InternalAddressPolicy.DENY_INTERNAL.rejects(InetAddress.getByName("10.0.0.1")));
        assertTrue(InternalAddressPolicy.DENY_INTERNAL.rejects(InetAddress.getByName("127.0.0.1")));
        assertTrue(InternalAddressPolicy.DENY_INTERNAL.rejects(InetAddress.getByName("169.254.1.1")));
        // IPv6 唯一本地地址 fc00::/7，Java 的 isSiteLocalAddress 不覆盖，靠手动判定
        assertTrue(InternalAddressPolicy.DENY_INTERNAL.rejects(InetAddress.getByName("fd00::1")));
        assertFalse(InternalAddressPolicy.DENY_INTERNAL.rejects(InetAddress.getByName("8.8.8.8")));

        assertFalse(InternalAddressPolicy.ALLOW_INTERNAL.rejects(InetAddress.getByName("10.0.0.1")));
        assertFalse(InternalAddressPolicy.ALLOW_INTERNAL.rejects(InetAddress.getByName("127.0.0.1")));
        assertFalse(InternalAddressPolicy.ALLOW_INTERNAL.rejects(InetAddress.getByName("fd00::1")));
        assertTrue(InternalAddressPolicy.ALLOW_INTERNAL.rejects(InetAddress.getByName("169.254.1.1")));
    }

    @Test
    void policySupplier_shouldBeReadPerCheck() {
        // 白名单来自可刷新的配置对象：Guard 不缓存策略，改配置后无需重建实例即刻生效
        List<String> mutableWhitelist = new java.util.ArrayList<>();
        HttpTargetGuard guard = new HttpTargetGuard(
            () -> HttpTargetPolicy.of(mutableWhitelist, InternalAddressPolicy.DENY_INTERNAL));

        assertThrows(HttpTargetForbiddenException.class, () -> guard.checkAllowed("http://127.0.0.1/x"));
        mutableWhitelist.add("127.0.0.1");
        assertDoesNotThrow(() -> guard.checkAllowed("http://127.0.0.1/x"));
    }

    @Test
    void policyDefaults_shouldFallBackToStrictest() {
        // null 白名单 + null 策略 → 空白名单 + 拒内网（默认值一律取更严的一侧）
        HttpTargetGuard guard = new HttpTargetGuard(HttpTargetPolicy.of(null, null));

        assertThrows(HttpTargetForbiddenException.class, () -> guard.checkAllowed("http://127.0.0.1/x"));
        assertDoesNotThrow(() -> guard.checkAllowed("http://8.8.8.8/x"));
    }
}
