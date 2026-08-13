package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SemanticCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义缓存单测。
 *
 * <p>重心不在"能不能命中"，而在<b>什么情况下绝不能命中</b>：在客服场景无差别缓存会造成数据泄露
 * （把 A 用户的订单信息返回给 B），三道安全闸门每一道都必须有对应用例守住。</p>
 *
 * <p>意图分类用 mock 而非真实规则：这里测的是缓存决策，不该被意图规则的实现细节牵连。</p>
 * @author owlzhangfq@gmail.com
 */
class SemanticCacheServiceTest {

    /** 语义相近的两个问法，向量几乎重合。 */
    private static final float[] VECTOR_INVOICE = {1.0f, 0.0f, 0.0f};
    private static final float[] VECTOR_INVOICE_ALIKE = {0.999f, 0.045f, 0.0f};
    /** 完全不同的问题，向量正交。 */
    private static final float[] VECTOR_SHIPPING = {0.0f, 1.0f, 0.0f};

    private SemanticCacheStore store;
    private EmbeddingClient embeddingClient;
    private MultiAgentOrchestrator orchestrator;
    private SemanticCacheProperties properties;
    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        store = new InMemorySemanticCacheStore();
        embeddingClient = mock(EmbeddingClient.class);
        orchestrator = mock(MultiAgentOrchestrator.class);
        properties = new SemanticCacheProperties();
        properties.setEnabled(true);
        // 默认所有问题都判成 consult（白名单内），需要测别的意图时在用例里覆盖
        when(orchestrator.fastRouteIntent(anyString())).thenReturn(Optional.of("consult"));
        service = newService();
    }

    private SemanticCacheService newService() {
        return new SemanticCacheService(store, embeddingClient, orchestrator,
            new TenantResolver(new CustomerWorkProperties()), properties);
    }

    private void stubVector(String question, float[] vector) {
        when(embeddingClient.embedQuery(question)).thenReturn(vector);
    }

    // ---------- 安全闸门：这三条是这个功能能不能上线的前提 ----------

    @Test
    void questionWithOrderNumber_shouldNeverBeCached() {
        // "帮我查订单 20260813001" —— 两个用户问同样的话，答案完全不同
        assertFalse(service.cacheable("帮我查一下订单 20260813001 到哪了", "consult"),
            "含订单号的问题必然是针对某个人的，缓存它就是把 A 的信息发给 B");
    }

    @Test
    void answerWithOrderNumber_shouldNeverBeCached() {
        stubVector("发票怎么开", VECTOR_INVOICE);

        service.put("tenantA:sess-1", "发票怎么开", "您的订单 20260813001 已开票");

        assertTrue(store.findCandidates("tenantA", "consult", 0L, 10).isEmpty(),
            "答案里带单号是'这条回答依赖个人数据'的直接证据，比意图判断更可信");
    }

    @Test
    void nonWhitelistedIntent_shouldNeverBeCached() {
        // order/refund 天然依赖个人数据，不在默认白名单里
        assertFalse(service.cacheable("我的快递到哪了", "order"));
        assertFalse(service.cacheable("我要退款", "refund"));
        assertTrue(service.cacheable("发票怎么开", "consult"), "政策咨询类对所有人答案相同，可缓存");
    }

    @Test
    void unclassifiableIntent_shouldNotBeCached() {
        // 快车道判不出意图（模糊/多意图）：判不清意图就更判不清这个答案能不能复用
        assertFalse(service.cacheable("你好在吗", null));
    }

    @Test
    void policyNumbersInText_shouldNotBeMistakenForPersonalData() {
        // "7天无理由""满99包邮"这类政策数字都在 5 位以内，不该被误伤
        assertFalse(service.containsPersonalIdentifier("满99元包邮，7天无理由退货"));
        assertTrue(service.containsPersonalIdentifier("单号 SF1234567890"));
    }

    // ---------- 命中与未命中 ----------

    @Test
    void similarQuestion_shouldHitAndReuseAnswer() {
        stubVector("发票怎么开", VECTOR_INVOICE);
        stubVector("怎么开具发票呀", VECTOR_INVOICE_ALIKE);
        service.put("tenantA:sess-1", "发票怎么开", "订单详情页可自助申请电子发票。");

        Optional<String> hit = service.lookup("tenantA:sess-2", "怎么开具发票呀");

        assertEquals("订单详情页可自助申请电子发票。", hit.orElseThrow(),
            "换个问法是同一个问题——这正是用向量而非字符串比对的理由");
    }

    @Test
    void dissimilarQuestion_shouldMiss() {
        stubVector("发票怎么开", VECTOR_INVOICE);
        stubVector("运费怎么算", VECTOR_SHIPPING);
        service.put("tenantA:sess-1", "发票怎么开", "订单详情页可自助申请电子发票。");

        assertTrue(service.lookup("tenantA:sess-2", "运费怎么算").isEmpty());
    }

    @Test
    void similarityBelowThreshold_shouldMiss() {
        properties.setSimilarityThreshold(0.999999d);
        service = newService();
        stubVector("发票怎么开", VECTOR_INVOICE);
        stubVector("怎么开具发票呀", VECTOR_INVOICE_ALIKE);
        service.put("tenantA:sess-1", "发票怎么开", "答案");

        // 阈值定低了会把"换货"答成"退货"；宁可少命中，不可答错
        assertTrue(service.lookup("tenantA:sess-2", "怎么开具发票呀").isEmpty());
    }

    @Test
    void differentScope_shouldNotShareCache() {
        stubVector("发票怎么开", VECTOR_INVOICE);
        stubVector("怎么开具发票呀", VECTOR_INVOICE_ALIKE);
        service.put("tenantA:sess-1", "发票怎么开", "A 租户的答案");

        // 不同租户的政策口径本就不同，缓存不能串
        assertTrue(service.lookup("tenantB:sess-1", "怎么开具发票呀").isEmpty());
    }

    @Test
    void hitShouldIncrementCounter() {
        stubVector("发票怎么开", VECTOR_INVOICE);
        stubVector("怎么开具发票呀", VECTOR_INVOICE_ALIKE);
        service.put("tenantA:sess-1", "发票怎么开", "答案");

        service.lookup("tenantA:sess-2", "怎么开具发票呀");

        List<SemanticCacheEntry> entries = store.findCandidates("tenantA", "consult", 0L, 10);
        assertEquals(1L, entries.get(0).hitCount(), "命中次数用于容量淘汰时保留高频条目");
    }

    // ---------- 开关与降级 ----------

    @Test
    void disabled_shouldBeCompletelyInert() {
        properties.setEnabled(false);
        service = newService();
        stubVector("发票怎么开", VECTOR_INVOICE);

        service.put("tenantA:sess-1", "发票怎么开", "答案");

        assertTrue(service.lookup("tenantA:sess-1", "发票怎么开").isEmpty());
        assertEquals(0L, store.count("tenantA"), "关闭时不该写入任何东西");
    }

    @Test
    void withoutEmbeddingClient_shouldSilentlyDisable() {
        // 缺 API Key 时没有向量，谈不上语义命中；应静默失效而不是报错阻断主链路
        SemanticCacheService noEmbedding = new SemanticCacheService(store, null, orchestrator,
            new TenantResolver(new CustomerWorkProperties()), properties);

        noEmbedding.put("tenantA:sess-1", "发票怎么开", "答案");

        assertTrue(noEmbedding.lookup("tenantA:sess-1", "发票怎么开").isEmpty());
        assertEquals(0L, store.count("tenantA"));
    }

    @Test
    void embeddingFailure_shouldDegradeToMiss() {
        when(embeddingClient.embedQuery(anyString())).thenThrow(new IllegalStateException("embedding down"));

        // 缓存是加速手段，它的故障不该让用户问不了问题
        assertTrue(service.lookup("tenantA:sess-1", "发票怎么开").isEmpty());
    }

    @Test
    void expiredEntry_shouldNotBeReturned() {
        properties.setTtlSeconds(1);
        service = newService();
        stubVector("发票怎么开", VECTOR_INVOICE);
        stubVector("怎么开具发票呀", VECTOR_INVOICE_ALIKE);
        // 直接写一条 2 小时前的条目，绕开 put 的当前时间戳
        store.save(SemanticCacheEntry.of("tenantA", "consult", "发票怎么开",
            "1.0,0.0,0.0", "旧答案", System.currentTimeMillis() - 7_200_000L));

        assertTrue(service.lookup("tenantA:sess-2", "怎么开具发票呀").isEmpty(),
            "政策会变，缓存不该永久有效");
    }

    @Test
    void tooShortQuestion_shouldNotBeCached() {
        assertFalse(service.cacheable("嗯", "consult"), "太短的问题没有缓存价值且极易误命中");
    }

    @Test
    void tooLongQuestion_shouldNotBeCached() {
        assertFalse(service.cacheable("发票".repeat(200), "consult"), "长问题几乎不会重复，只是白占容量");
    }

    @Test
    void capacityShouldBeEnforced() {
        properties.setMaxSize(2);
        service = newService();
        for (int i = 0; i < 5; i++) {
            String question = "问题内容编号" + (char) ('A' + i);
            stubVector(question, new float[]{i, 1.0f, 0.0f});
            service.put("tenantA:sess-1", question, "答案" + i);
        }

        // 相似度在应用层逐条算，条目无上限增长会让查缓存比调模型还慢
        assertTrue(store.count("tenantA") <= 2, "超出容量应淘汰最久未命中的，实际=" + store.count("tenantA"));
    }

    @Test
    void list_shouldRankByHitCount() {
        stubVector("发票怎么开", VECTOR_INVOICE);
        stubVector("怎么开具发票呀", VECTOR_INVOICE_ALIKE);
        stubVector("运费怎么算", VECTOR_SHIPPING);
        service.put("tenantA:sess-1", "发票怎么开", "发票答案");
        service.put("tenantA:sess-1", "运费怎么算", "运费答案");
        service.lookup("tenantA:sess-2", "怎么开具发票呀");   // 让发票那条命中一次

        List<SemanticCacheEntry> listed = service.list("tenantA", 10);

        assertEquals(2, listed.size());
        // 运营看的是"哪些缓存真的在被复用"，命中 0 次的只是白占容量
        assertEquals("发票怎么开", listed.get(0).question());
        assertEquals(1L, listed.get(0).hitCount());
    }

    @Test
    void evict_shouldRemoveSingleEntry() {
        stubVector("发票怎么开", VECTOR_INVOICE);
        service.put("tenantA:sess-1", "发票怎么开", "答案");
        SemanticCacheEntry entry = service.list("tenantA", 10).get(0);

        assertTrue(service.evict(entry.id()), "发现某条答得不对时应能定点删除，不必清空整个分区");
        assertEquals(0L, store.count("tenantA"));
    }

    @Test
    void evict_unknownId_shouldReturnFalse() {
        assertFalse(service.evict(999L));
    }

    @Test
    void invalidate_shouldClearScope() {
        stubVector("发票怎么开", VECTOR_INVOICE);
        service.put("tenantA:sess-1", "发票怎么开", "答案");

        assertEquals(1, service.invalidate("tenantA"), "知识库或提示词改过之后，旧答案不再可信");
        assertEquals(0L, store.count("tenantA"));
    }
}
