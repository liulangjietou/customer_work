package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.infra.config.properties.SemanticCacheProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义缓存的可观测埋点。
 *
 * <h3>为什么这些指标值得单独测</h3>
 * <p>这套缓存此前一个指标都没有，于是「命中率多少」「候选有没有被截断」全靠猜。
 * 报告 P1-13 建议把召回改成向量检索，理由是新条目冷启动饿死——
 * 而那个理由经实测<b>不成立</b>（新条目写入时 lastHitAtMs 就是创建时刻，排在候选最前）。
 * 真实代价是 maxSize 与 maxCandidates 的比值：单次查询只看得到一部分缓存。
 * 该不该为此改存储格式，要由这几个指标回答，不该由谁的直觉回答。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class SemanticCacheMetricsTest {

    private static final String LOOKUP = "customerwork.semanticcache.lookup";
    private static final String TRUNCATED = "customerwork.semanticcache.candidates.truncated";
    private static final String MISS_SCORE = "customerwork.semanticcache.miss.bestscore";

    private InMemorySemanticCacheStore store;
    private EmbeddingClient embeddingClient;
    private MultiAgentOrchestrator orchestrator;
    private SemanticCacheProperties properties;
    private MeterRegistry registry;
    private SemanticCacheService service;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** 问题文本都写得足够长：{@code minQuestionLength} 默认 4，更短的问题压根不进缓存。 */
    @BeforeEach
    void setUp() {
        store = new InMemorySemanticCacheStore();
        embeddingClient = mock(EmbeddingClient.class);
        orchestrator = mock(MultiAgentOrchestrator.class);
        properties = new SemanticCacheProperties();
        properties.setEnabled(true);
        registry = new SimpleMeterRegistry();
        when(orchestrator.fastRouteIntent(anyString())).thenReturn(Optional.of("consult"));
        service = new SemanticCacheService(store, embeddingClient, orchestrator,
            new TenantResolver(new CustomerWorkProperties()),
            properties, registry);
    }

    @Test
    @DisplayName("命中与未命中分别计数，命中率因此可算")
    void countsHitAndMiss() {
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[]{1f, 0f});

        service.lookup("s1", "七天无理由怎么退");
        assertEquals(1.0, counter(LOOKUP, "miss"), "缓存空时应记一次未命中");

        service.put("s1", "七天无理由怎么退", "签收后七天内可退");
        service.lookup("s1", "七天无理由怎么退");
        assertEquals(1.0, counter(LOOKUP, "hit"));
    }

    @Test
    @DisplayName("缓存关闭时记 skip，不与真实未命中混为一谈")
    void countsSkipWhenDisabled() {
        properties.setEnabled(false);

        service.lookup("s1", "任意问题");

        assertEquals(1.0, counter(LOOKUP, "skip"),
            "没查过和查了没命中是两回事，混在一起算命中率会低得莫名其妙");
        assertEquals(0.0, counter(LOOKUP, "miss"));
    }

    /**
     * 候选触顶是「该不该改成向量检索」的判据。
     *
     * <p>它一直是 0，说明候选根本没触过顶，扩大候选毫无意义；
     * 频繁触顶才说明有更相似的条目没进入比较范围。</p>
     */
    @Test
    @DisplayName("候选触顶时单独计数")
    void countsTruncatedCandidates() {
        properties.setMaxCandidates(2);
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[]{1f, 0f});

        service.put("s1", "退货运费谁承担", "无理由退货由买家承担");
        service.put("s1", "发票什么时候开具", "订单完成次日开具");
        service.put("s1", "保修期有多久呢", "整机十二个月");
        service.lookup("s1", "配送要几天到货");

        assertEquals(1.0, counter(TRUNCATED),
            "候选数达到上限却没计数——那么「要不要扩大候选」这个决定就还是只能靠猜");
    }

    @Test
    @DisplayName("候选未触顶时不计数")
    void doesNotCountWhenNotTruncated() {
        properties.setMaxCandidates(100);
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[]{1f, 0f});

        service.put("s1", "退货运费谁承担", "无理由退货由买家承担");
        service.lookup("s1", "发票什么时候开具");

        assertEquals(0.0, counter(TRUNCATED));
    }

    /**
     * 未命中时的最高相似度分布回答「差多少才命中」。
     *
     * <p>大量落在阈值下沿说明阈值太严——调一个配置就能解决，比改存储格式便宜得多。</p>
     */
    @Test
    @DisplayName("未命中时记录最高相似度，供判断阈值是否过严")
    void recordsBestScoreOnMiss() {
        properties.setSimilarityThreshold(0.99);
        when(embeddingClient.embedQuery("退货运费谁承担")).thenReturn(new float[]{1f, 0f});
        service.put("s1", "退货运费谁承担", "无理由退货由买家承担");

        // 与缓存条目有相当高但不足阈值的相似度
        when(embeddingClient.embedQuery("发票什么时候开具")).thenReturn(new float[]{0.98f, 0.199f});
        service.lookup("s1", "发票什么时候开具");

        assertTrue(registry.summary(MISS_SCORE).count() >= 1,
            "未命中时没记最高分——就无法分辨「差一点」和「毫不相干」，"
                + "而这两种情况的处置完全不同");
        assertTrue(registry.summary(MISS_SCORE).max() > 0.5);
    }

    @Test
    @DisplayName("没有 MeterRegistry 时缓存照常工作")
    void worksWithoutRegistry() {
        SemanticCacheService noMetrics = new SemanticCacheService(store, embeddingClient, orchestrator,
            new TenantResolver(new CustomerWorkProperties()),
            properties, null);
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[]{1f, 0f});

        noMetrics.put("s1", "退货运费谁承担", "买家承担");
        assertTrue(noMetrics.lookup("s1", "退货运费谁承担").isPresent(),
            "可观测缺失不该让缓存本身不可用");
    }

    private double counter(String name, String result) {
        io.micrometer.core.instrument.Counter c = registry.find(name).tag("result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    private double counter(String name) {
        io.micrometer.core.instrument.Counter c = registry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }
}
