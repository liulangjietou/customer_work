package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeBaseEndpoint;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeSearchException;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeSearchOps;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeSearchSettings;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 外部 RAG 知识库检索客户端（admin 侧调用壳）。
 *
 * <p><b>执行核心不在本类</b>：HTTP 调用、响应解析、多库并发合并、阈值过滤、降级与网络异常翻译都在
 * starter 的 {@link KnowledgeSearchOps}（无 Spring 依赖的纯执行核心）。本类只做三件事：把
 * {@link AdminRagProperties} 映射成 starter 的 {@link KnowledgeSearchSettings}、把
 * {@link KnowledgeBaseHttpGuard} 绑好的地址策略交给执行核心、把 starter 异常转成 {@link BizException}
 * 交给全局异常处理器。</p>
 *
 * <p><b>两层 API 的职责分工</b>（语义与 starter 一致）：
 * <ul>
 *   <li>{@link #searchOne}：单库检索，返回<b>原始</b>召回（不做阈值过滤、不截断），失败 fast fail
 *       抛 {@link BizException}——供保存门禁与连通性测试使用；</li>
 *   <li>{@link #searchAll}：多库并发检索 + 阈值过滤 + 合并取 top-n，任何失败/超时都吞掉并返回已成功
 *       的部分——供对话链路使用，检索绝不打断对话。</li>
 * </ul></p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class KnowledgeSearchClient {

    /** 超时诊断文案里提示给使用者的配置项名（本模块的真实配置键）。 */
    private static final String REQUEST_TIMEOUT_CONFIG_KEY = "admin.rag.request-timeout-seconds";

    private final KnowledgeSearchOps searchOps;

    public KnowledgeSearchClient(KnowledgeBaseHttpGuard httpGuard, AdminRagProperties properties) {
        this.searchOps = new KnowledgeSearchOps(httpGuard.targetGuard(), toSettings(properties));
    }

    /**
     * {@code admin.rag.*} → starter 的中立配置 POJO（白名单不在这里，由 Guard 承载）。
     * 包级可见便于同包单测直接核对映射，漏一项就会让超时/连接行为悄悄用回 starter 默认值。
     */
    static KnowledgeSearchSettings toSettings(AdminRagProperties properties) {
        KnowledgeSearchSettings settings = new KnowledgeSearchSettings();
        settings.setConnectTimeoutSeconds(properties.getConnectTimeoutSeconds());
        settings.setRequestTimeoutSeconds(properties.getRequestTimeoutSeconds());
        settings.setRetrievalTimeoutSeconds(properties.getRetrievalTimeoutSeconds());
        settings.setRequestTimeoutConfigKey(REQUEST_TIMEOUT_CONFIG_KEY);
        return settings;
    }

    /**
     * 单库检索，返回原始召回（不过滤、不截断）。任何失败（地址被拦截 / 网络异常 / 非 200 /
     * code != OK / 响应结构非法）均抛 {@link BizException}。
     */
    public List<KnowledgeNode> searchOne(KnowledgeBaseEndpoint endpoint, String query) {
        try {
            return searchOps.searchOne(endpoint, query);
        } catch (HttpTargetForbiddenException e) {
            throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, e.getMessage());
        } catch (KnowledgeSearchException e) {
            throw new BizException(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED, e.getMessage());
        }
    }

    /**
     * 多库并发检索 + 阈值过滤 + 合并按 score 倒排取 top-n。
     * 单库失败/整体超时都降级为空召回，不抛异常（对话链路的可用性优先于检索完整性），故无需转译。
     */
    public List<KnowledgeNode> searchAll(List<KnowledgeBaseEndpoint> endpoints, String query) {
        return searchOps.searchAll(endpoints, query);
    }

    /**
     * 解析自定义请求头（JSON 对象字符串 → header map），保留头被忽略。
     * 保存时校验（{@code KnowledgeBaseService}）与运行时解析共用 starter 同一份实现，避免两处规则漂移。
     *
     * @throws IllegalArgumentException JSON 非法或不是对象结构
     */
    public static Map<String, String> parseExtraHeaders(ObjectMapper mapper, String extraHeaders) {
        return KnowledgeSearchOps.parseExtraHeaders(mapper, extraHeaders);
    }
}
