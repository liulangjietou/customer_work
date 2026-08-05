package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link KnowledgeBaseHttpGuard} 单测：本类是 starter {@code HttpTargetGuard} 的薄壳，故这里只验证
 * <b>属于薄壳的三件事</b>——策略绑定（放行内网、只拦链路本地，与系统工具的信任边界相反，这正是本类
 * 独立存在的理由）、白名单绑定（{@code admin.rag.allowed-hosts}）、异常转译（错误码为
 * {@link ResultCode#KNOWLEDGE_BASE_HTTP_FORBIDDEN}）。判定算法本身在 starter 的
 * {@code HttpTargetGuardTest} 覆盖，不在此重复。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeBaseHttpGuardTest {

    private KnowledgeBaseHttpGuard guard(String... allowedHosts) {
        AdminRagProperties properties = new AdminRagProperties();
        properties.setAllowedHosts(List.of(allowedHosts));
        return new KnowledgeBaseHttpGuard(properties);
    }

    @Test
    void defaultMode_shouldBindAllowInternalPolicy() {
        // RAG 一般内网部署（用户本机就是 localhost:20002），默认策略必须放行，否则功能直接不可用
        assertDoesNotThrow(() -> guard().checkAllowed("http://localhost:20002"));
        assertDoesNotThrow(() -> guard().checkAllowed("http://127.0.0.1:20002/"));
        assertDoesNotThrow(() -> guard().checkAllowed("http://192.168.1.10:8080"));
        // 169.254.169.254 是云元数据服务，绝无可能是 RAG 服务，默认模式下唯一被拦的一类地址
        assertThrows(BizException.class, () -> guard().checkAllowed("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void whitelistMode_shouldBindConfiguredAllowedHosts() {
        KnowledgeBaseHttpGuard guard = guard("rag.internal.corp", "*.example.com");

        assertDoesNotThrow(() -> guard.checkAllowed("http://rag.internal.corp:20002"));
        assertDoesNotThrow(() -> guard.checkAllowed("https://kb.example.com/api"));
        assertThrows(BizException.class, () -> guard.checkAllowed("http://localhost:20002"));
        assertThrows(BizException.class, () -> guard.checkAllowed("https://evil.com"));
    }

    @Test
    void blocked_shouldBeTranslatedToBizExceptionWithKnowledgeBaseCode() {
        BizException schemeEx = assertThrows(BizException.class, () -> guard().checkAllowed("file:///etc/passwd"));
        assertEquals(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, schemeEx.getResultCode());

        BizException blankEx = assertThrows(BizException.class, () -> guard().checkAllowed(""));
        assertEquals(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, blankEx.getResultCode());

        BizException hostlessEx = assertThrows(BizException.class, () -> guard().checkAllowed("http:///no-host"));
        assertEquals(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, hostlessEx.getResultCode());

        BizException linkLocalEx = assertThrows(BizException.class,
            () -> guard().checkAllowed("http://169.254.169.254/x"));
        assertEquals(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, linkLocalEx.getResultCode());
    }
}
