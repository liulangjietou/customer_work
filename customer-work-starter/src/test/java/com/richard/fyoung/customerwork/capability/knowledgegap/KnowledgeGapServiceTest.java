package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.properties.KnowledgeGapProperties;
import com.richard.fyoung.customerwork.tool.KnowledgeBaseTools;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识盲区分析单测。
 *
 * <p>最关键的一条是 {@link #toolMiss_shouldBeRecordedThroughContract()}：埋点判定走
 * {@code KnowledgeBackend.isMiss} 这个契约，而不是这里自己认的字符串——后端改文案时会连带改常量，
 * 埋点不会静默失效。</p>
 * @author owlzhangfq@gmail.com
 */
class KnowledgeGapServiceTest {

    private KnowledgeGapStore store;
    private KnowledgeGapProperties properties;
    private KnowledgeGapService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryKnowledgeGapStore();
        properties = new KnowledgeGapProperties();
        service = new KnowledgeGapService(store, new TenantResolver(new CustomerWorkProperties()), properties);
    }

    @Test
    void repeatedMiss_shouldAccumulateCount() {
        service.recordMiss("tenantA:sess-1", "你们支持货到付款吗");
        service.recordMiss("tenantA:sess-2", "你们支持货到付款吗");
        service.recordMiss("tenantA:sess-3", "你们支持货到付款吗");

        List<KnowledgeGap> gaps = service.topGaps("tenantA", 10);
        assertEquals(1, gaps.size(), "同一问题聚合成一条，不是逐条流水");
        assertEquals(3L, gaps.get(0).missCount());
    }

    @Test
    void topGaps_shouldRankByMissCount() {
        service.recordMiss("tenantA:s1", "你们支持货到付款吗");
        service.recordMiss("tenantA:s2", "你们支持货到付款吗");
        service.recordMiss("tenantA:s3", "能开专票吗");

        List<KnowledgeGap> gaps = service.topGaps("tenantA", 10);

        assertEquals("你们支持货到付款吗", gaps.get(0).question(), "问得最多的排最前，那才是最该补的");
        assertEquals(2L, gaps.get(0).missCount());
    }

    @Test
    void whitespaceVariants_shouldBeSameGap() {
        service.recordMiss("tenantA:s1", "能开专票吗");
        service.recordMiss("tenantA:s2", "  能开专票吗  ");

        assertEquals(1, service.topGaps("tenantA", 10).size(), "同一个问题不该因为多打了空格被算成两条");
        assertEquals(2L, service.topGaps("tenantA", 10).get(0).missCount());
    }

    @Test
    void tooShortQuestion_shouldBeIgnored() {
        service.recordMiss("tenantA:s1", "嗯");
        service.recordMiss("tenantA:s2", "在吗");

        assertTrue(service.topGaps("tenantA", 10).isEmpty(),
            "太短的本就不该指望知识库命中，计进去只会淹没真正的盲区");
    }

    @Test
    void disabled_shouldRecordNothing() {
        properties.setEnabled(false);

        service.recordMiss("tenantA:s1", "你们支持货到付款吗");

        assertTrue(service.topGaps("tenantA", 10).isEmpty());
    }

    @Test
    void differentScopes_shouldNotMix() {
        service.recordMiss("tenantA:s1", "你们支持货到付款吗");
        service.recordMiss("tenantB:s1", "你们支持货到付款吗");

        assertEquals(1, service.topGaps("tenantA", 10).size());
        assertEquals(1L, service.topGaps("tenantA", 10).get(0).missCount());
    }

    @Test
    void firstSeenTime_shouldBePreservedAcrossMisses() {
        service.recordMiss("tenantA:s1", "你们支持货到付款吗");
        KnowledgeGap first = service.topGaps("tenantA", 10).get(0);
        service.recordMiss("tenantA:s2", "你们支持货到付款吗");
        KnowledgeGap second = service.topGaps("tenantA", 10).get(0);

        assertEquals(first.firstSeenAtMs(), second.firstSeenAtMs(),
            "首次出现时间是'这个问题何时开始查不到'，不该被后续未命中刷新");
        assertTrue(second.lastSeenAtMs() >= first.lastSeenAtMs());
    }

    @Test
    void toolMiss_shouldBeRecordedThroughContract() {
        // 命中与未命中各走一次，验证埋点只在未命中时触发、且判定走接口契约
        KnowledgeBackend missBackend = query -> Mono.just(KnowledgeBackend.NO_HIT_REPLY);
        KnowledgeBackend hitBackend = query -> Mono.just("知识库召回如下：\n· 支持货到付款");

        new KnowledgeBaseTools(missBackend, service).searchKnowledge("你们支持货到付款吗").block();
        new KnowledgeBaseTools(hitBackend, service).searchKnowledge("七天无理由怎么算").block();

        List<KnowledgeGap> gaps = service.topGaps(TenantResolver.DEFAULT_TENANT, 10);
        assertEquals(1, gaps.size(), "只有未命中才该被记为盲区");
        assertEquals("你们支持货到付款吗", gaps.get(0).question());
    }

    @Test
    void toolWithoutGapService_shouldBehaveAsBefore() {
        KnowledgeBackend missBackend = query -> Mono.just(KnowledgeBackend.NO_HIT_REPLY);

        String result = new KnowledgeBaseTools(missBackend).searchKnowledge("随便问问什么东西").block();

        assertEquals(KnowledgeBackend.NO_HIT_REPLY, result, "未装配盲区分析时工具行为完全不变");
    }
}
