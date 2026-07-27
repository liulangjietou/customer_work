package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeBaseHttpGuard} 单测：默认放行内网（与系统工具的信任边界相反，这是本类存在的理由）、
 * 拦截非法协议/链路本地地址、白名单收紧模式的精确与通配匹配。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeBaseHttpGuardTest {

    private KnowledgeBaseHttpGuard guard(String... allowedHosts) {
        AdminRagProperties properties = new AdminRagProperties();
        properties.setAllowedHosts(List.of(allowedHosts));
        return new KnowledgeBaseHttpGuard(properties);
    }

    @Test
    void checkAllowed_shouldPassLocalhost_byDefault() {
        // RAG 一般内网部署（用户本机就是 localhost:20002），默认策略必须放行，否则功能直接不可用
        assertDoesNotThrow(() -> guard().checkAllowed("http://localhost:20002"));
        assertDoesNotThrow(() -> guard().checkAllowed("http://127.0.0.1:20002/"));
    }

    @Test
    void checkAllowed_shouldPassPrivateNetwork_byDefault() {
        assertDoesNotThrow(() -> guard().checkAllowed("http://192.168.1.10:8080"));
        assertDoesNotThrow(() -> guard().checkAllowed("http://10.0.0.7"));
    }

    @Test
    void checkAllowed_shouldRejectLinkLocalMetadataAddress_byDefault() {
        // 169.254.169.254 是云元数据服务，绝无可能是 RAG 服务，默认模式下唯一被拦的一类地址
        assertThrows(BizException.class, () -> guard().checkAllowed("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void checkAllowed_shouldRejectNonHttpScheme() {
        assertThrows(BizException.class, () -> guard().checkAllowed("file:///etc/passwd"));
        assertThrows(BizException.class, () -> guard().checkAllowed("ftp://example.com"));
    }

    @Test
    void checkAllowed_shouldRejectBlankOrHostlessUrl() {
        assertThrows(BizException.class, () -> guard().checkAllowed(""));
        assertThrows(BizException.class, () -> guard().checkAllowed("http:///no-host"));
    }

    @Test
    void checkAllowed_shouldOnlyAllowWhitelistedHosts_whenWhitelistConfigured() {
        KnowledgeBaseHttpGuard guard = guard("rag.internal.corp", "*.example.com");

        assertDoesNotThrow(() -> guard.checkAllowed("http://rag.internal.corp:20002"));
        assertDoesNotThrow(() -> guard.checkAllowed("https://kb.example.com/api"));
        assertThrows(BizException.class, () -> guard.checkAllowed("http://localhost:20002"));
        assertThrows(BizException.class, () -> guard.checkAllowed("https://evil.com"));
    }

    @Test
    void hostWhitelisted_shouldMatchExactAndWildcardSuffix() {
        KnowledgeBaseHttpGuard guard = guard();
        List<String> whitelist = List.of("rag.internal.corp", "*.example.com");

        assertTrue(guard.hostWhitelisted("RAG.INTERNAL.CORP", whitelist), "精确匹配应忽略大小写");
        assertTrue(guard.hostWhitelisted("a.b.example.com", whitelist), "通配后缀应匹配多级子域");
        assertFalse(guard.hostWhitelisted("example.com", whitelist), "*.example.com 不匹配裸域名");
        assertFalse(guard.hostWhitelisted("notexample.com", whitelist));
    }
}
