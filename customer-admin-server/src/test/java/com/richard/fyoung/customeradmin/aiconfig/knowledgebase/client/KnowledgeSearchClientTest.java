package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customerwork.rag.search.KnowledgeBaseEndpoint;
import com.richard.fyoung.customerwork.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.rag.search.KnowledgeSearchSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeSearchClient} 单测：本类是 starter {@code KnowledgeSearchOps} 的调用壳，故这里只验证
 * <b>属于薄壳的三件事</b>——异常转译（地址拦截 / 检索失败各自的业务错误码）、
 * {@code admin.rag.*} 到 starter 配置 POJO 的映射（超时诊断文案里必须出现本模块的真实配置键）、
 * 静态解析方法的委派。检索/解析/合并/降级等算法本身在 starter 的 {@code KnowledgeSearchOpsTest}
 * 覆盖，不在此重复。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeSearchClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 保证连接被拒的地址：1 号端口不可能有服务在监听，且是环回地址（默认策略放行内网）。 */
    private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1";

    private KnowledgeSearchClient client(String... allowedHosts) {
        AdminRagProperties properties = new AdminRagProperties();
        properties.setAllowedHosts(List.of(allowedHosts));
        return new KnowledgeSearchClient(new KnowledgeBaseHttpGuard(properties), properties);
    }

    private KnowledgeBaseEndpoint endpoint(String baseUrl) {
        return new KnowledgeBaseEndpoint(1L, "kb", baseUrl, "app_1", "sk-test",
            "application/json", "", 5, BigDecimal.ZERO);
    }

    /** 地址被安全策略拦截：必须是 KNOWLEDGE_BASE_HTTP_FORBIDDEN，不能被当成"检索失败"混过去。 */
    @Test
    void searchOne_shouldTranslateForbiddenTarget_toHttpForbiddenCode() {
        BizException e = assertThrows(BizException.class,
            () -> client("rag.internal.corp").searchOne(endpoint(UNREACHABLE_BASE_URL), "问题"));

        assertEquals(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, e.getResultCode());
    }

    /** 检索链路失败（这里是连接被拒）：转 KNOWLEDGE_BASE_SEARCH_FAILED，并保留 starter 翻译好的排查提示。 */
    @Test
    void searchOne_shouldTranslateSearchFailure_toSearchFailedCode() {
        BizException e = assertThrows(BizException.class,
            () -> client().searchOne(endpoint(UNREACHABLE_BASE_URL), "问题"));

        assertEquals(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED, e.getResultCode());
        assertTrue(e.getMessage().contains("无法建立连接"), "应保留 starter 的可排查提示，实际=" + e.getMessage());
        assertTrue(e.getMessage().contains(UNREACHABLE_BASE_URL), "提示里应带上实际地址便于核对");
    }

    /**
     * 三个超时必须逐项映射到 starter（漏一项就会悄悄用回 starter 默认值），
     * 且超时诊断文案里的配置项名必须是<b>本模块的</b>真实配置键——starter 侧只是个中立占位，
     * 映射漏了会让排查的人拿到一个根本不存在的配置项。
     */
    @Test
    void toSettings_shouldMapAllTimeouts_andAdminRagConfigKey() {
        AdminRagProperties properties = new AdminRagProperties();
        properties.setConnectTimeoutSeconds(1);
        properties.setRequestTimeoutSeconds(3);
        properties.setRetrievalTimeoutSeconds(7);

        KnowledgeSearchSettings settings = KnowledgeSearchClient.toSettings(properties);

        assertEquals(1, settings.getConnectTimeoutSeconds());
        assertEquals(3, settings.getRequestTimeoutSeconds());
        assertEquals(7, settings.getRetrievalTimeoutSeconds());
        assertEquals("admin.rag.request-timeout-seconds", settings.getRequestTimeoutConfigKey());
    }

    /** 检索降级路径不抛异常，故无需转译：全部失败也只是空召回。 */
    @Test
    void searchAll_shouldNeverThrow_evenWhenAllKnowledgeBasesFail() {
        List<KnowledgeNode> nodes = assertDoesNotThrow(
            () -> client().searchAll(List.of(endpoint(UNREACHABLE_BASE_URL)), "问题"));

        assertTrue(nodes.isEmpty(), "检索失败绝不抛异常打断对话");
    }

    /** 静态解析委派给 starter：保存时校验与运行时解析共用同一份规则，不得在薄壳里另写一套。 */
    @Test
    void parseExtraHeaders_shouldDelegateToStarterImplementation() {
        Map<String, String> headers = KnowledgeSearchClient.parseExtraHeaders(MAPPER,
            "{\"authorization\":\"Bearer hack\",\"X-Ok\":\"1\"}");

        assertEquals(Map.of("X-Ok", "1"), headers);
        assertThrows(IllegalArgumentException.class, () -> KnowledgeSearchClient.parseExtraHeaders(MAPPER, "[1,2]"));
    }
}
