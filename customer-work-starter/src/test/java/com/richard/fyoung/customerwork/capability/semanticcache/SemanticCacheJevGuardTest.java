package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SemanticCacheProperties;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义缓存写入的 Jev 判定：只收紧不放宽，且确实接在了写入路径上。
 *
 * <p>正则只认 6 位以上数字，「我那个耳机什么时候到」这种没有数字却显然只属于某个人的问答会被放进缓存，
 * 下一个问同样问题的人会收到别人的物流答复。Jev 补的就是这一类。</p>
 */
class SemanticCacheJevGuardTest {

    private static final String SESSION = "tenantA:sess-1";
    private static final String SCOPE = "tenantA";
    private static final String QUESTION = "那个东西什么时候能到";
    private static final String ANSWER = "您购买的耳机已在派送途中，预计今天下午送达";

    private SemanticCacheStore store;
    private EmbeddingClient embeddingClient;
    private SemanticCacheService service;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        store = new InMemorySemanticCacheStore();
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        MultiAgentOrchestrator orchestrator = mock(MultiAgentOrchestrator.class);
        when(orchestrator.fastRouteIntent(anyString())).thenReturn(Optional.of("consult"));
        SemanticCacheProperties properties = new SemanticCacheProperties();
        properties.setEnabled(true);
        service = new SemanticCacheService(store, embeddingClient, orchestrator,
            new TenantResolver(new CustomerWorkProperties()), properties);
    }

    @Test
    @DisplayName("Jev 判为只属于某个用户：不写缓存，连 Embedding 都不调")
    void rejectsPersonalAnswerBeforeEmbedding() {
        service.setJevDecisionService(JevTestSupport.service(JevTestSupport.noul(0.8)));

        service.put(SESSION, QUESTION, ANSWER);

        assertTrue(cached().isEmpty(), "正则放过了这条，Jev 必须补上");
        verify(embeddingClient, never()).embedQuery(anyString());
    }

    @Test
    @DisplayName("Jev 判为通用问答：照常写入")
    void keepsGenericAnswer() {
        service.setJevDecisionService(JevTestSupport.service(JevTestSupport.noul(0.1)));

        service.put(SESSION, "发票怎么开", "在订单详情页点击申请发票即可");

        assertEquals(1, cached().size());
    }

    /**
     * Jev 不可用时必须回到未接入时的行为（照正则写入），而不是保守地一律不写——
     * 后者看着安全，实际是让缓存在 Jev 抖动期间整体失效。
     */
    @Test
    @DisplayName("Jev 不可用或出错：回到只看正则的旧行为")
    void fallsBackToRegexWhenJevUnavailable() {
        for (JevTestSupport.StubClient client : java.util.List.of(JevTestSupport.unavailable(), JevTestSupport.failing())) {
            store = new InMemorySemanticCacheStore();
            setUp();
            service.setJevDecisionService(JevTestSupport.service(client));

            service.put(SESSION, QUESTION, ANSWER);

            assertEquals(1, cached().size());
        }
    }

    @Test
    @DisplayName("正则已经拒绝的，不再问 Jev（只收紧，不存在让 Jev 放行的路径）")
    void doesNotAskJevWhenRegexAlreadyRejects() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.0);
        service.setJevDecisionService(JevTestSupport.service(client));

        service.put(SESSION, "发票怎么开", "您的订单 20260813001 已开票");

        assertTrue(cached().isEmpty());
        assertEquals(0, client.calls());
    }

    @Test
    @DisplayName("缓存判定开关关闭：不问 Jev")
    void disabledGuardSkipsJev() {
        TypeSafeProperties props = new TypeSafeProperties();
        props.getCacheGuard().setEnabled(false);
        JevTestSupport.StubClient client = JevTestSupport.noul(0.99);
        service.setJevDecisionService(JevTestSupport.service(client, props));

        service.put(SESSION, QUESTION, ANSWER);

        assertEquals(1, cached().size());
        assertEquals(0, client.calls());
    }

    private java.util.List<SemanticCacheEntry> cached() {
        return store.findCandidates(SCOPE, "consult", 0L, 10);
    }
}
