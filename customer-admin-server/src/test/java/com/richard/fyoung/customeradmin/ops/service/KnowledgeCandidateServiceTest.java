package com.richard.fyoung.customeradmin.ops.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidate;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateStore;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapCategory;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapClassification;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapPriority;
import com.richard.fyoung.customerwork.capability.knowledgegap.MybatisKnowledgeGapReviewStore;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

/** 来源核对失败不能进入候选写入；保存候选只读原始信号，不调用正式 FAQ 写入口。 */
class KnowledgeCandidateServiceTest {
    private static final String HASH = "a".repeat(64);
    private final KnowledgeCandidateStore store = mock(KnowledgeCandidateStore.class);
    private final OpsGatewayProvider provider = mock(OpsGatewayProvider.class);
    private final OpsGateway gateway = mock(OpsGateway.class);
    private final MybatisKnowledgeGapReviewStore reviews = mock(MybatisKnowledgeGapReviewStore.class);
    private final KnowledgeCandidateService service = new KnowledgeCandidateService(store, provider);
    private final KnowledgeCandidateSaveRequest request = new KnowledgeCandidateSaveRequest(HASH, 0, 3, "标题", "正文", "关键词");

    @BeforeEach
    void setup() {
        TenantContext.set("candidate-tenant");
        when(provider.get()).thenReturn(gateway);
        when(gateway.knowledgeGapReview()).thenReturn(reviews);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @ParameterizedTest
    @EnumSource(value = KnowledgeGapCategory.class, names = "KNOWLEDGE", mode = EnumSource.Mode.EXCLUDE)
    void nonKnowledgeCategoriesCannotBeTurnedIntoStaticKnowledge(KnowledgeGapCategory category) {
        source(category, KnowledgeGapClassification.Origin.MANUAL, 3);
        assertThrows(BizException.class, () -> service.save("candidate-id", request, 42));
        verifyNoInteractions(store);
    }

    @Test
    void ruleSuggestionIsNotAConfirmedKnowledgeGap() {
        source(KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapClassification.Origin.RULE, 3);
        assertThrows(BizException.class, () -> service.save("candidate-id", request, 42));
        verifyNoInteractions(store);
    }

    @Test
    void changedReviewAndMissingSourcePreventSavingTheOldEditor() {
        source(KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapClassification.Origin.MANUAL, 4);
        assertThrows(BizException.class, () -> service.save("candidate-id", request, 42));
        when(reviews.find("candidate-tenant", HASH)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.save("candidate-id", request, 42));
        verifyNoInteractions(store);
    }

    @Test
    void sourceFailureDoesNotPretendThereIsNoSourceOrWriteAnyCandidate() {
        when(reviews.find("candidate-tenant", HASH)).thenThrow(new DataAccessResourceFailureException("source unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.save("candidate-id", request, 42));
        verifyNoInteractions(store);
    }

    @Test
    void currentReviewedKnowledgeUsesTheTrustedTenantAndActorWithoutWritingFaq() {
        source(KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapClassification.Origin.MANUAL, 3);
        service.save("candidate-id", request, 42);
        verify(store).save(eq("candidate-tenant"), eq("candidate-id"), same(request), eq(42L), anyLong());
        verify(gateway).knowledgeGapReview();
        verifyNoMoreInteractions(gateway);
    }

    private void source(KnowledgeGapCategory category, KnowledgeGapClassification.Origin origin, long revision) {
        when(reviews.find("candidate-tenant", HASH)).thenReturn(Optional.of(new KnowledgeGap(
            HASH, "原始问题", "candidate-tenant", 1, 100, 100, null,
            new KnowledgeGapClassification(category, KnowledgeGapPriority.NORMAL, origin, "人工核对", revision, "42", 100L))));
    }

    @Test
    void exactCandidateVersionRequiresCurrentManualKnowledgeReview() {
        var candidate = candidate();
        when(store.find("candidate-tenant", "candidate-id")).thenReturn(Optional.of(candidate));
        source(KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapClassification.Origin.MANUAL, 3);
        assertEquals(candidate, service.requireCurrentVersion("candidate-id", 2));
        source(KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapClassification.Origin.MANUAL, 4);
        assertThrows(BizException.class, () -> service.requireCurrentVersion("candidate-id", 2));
        source(KnowledgeGapCategory.DEPENDENCY, KnowledgeGapClassification.Origin.MANUAL, 3);
        assertThrows(BizException.class, () -> service.requireCurrentVersion("candidate-id", 2));
    }

    @Test
    void staleRevisionCannotBeFrozenOrPublished() {
        when(store.find("candidate-tenant", "candidate-id")).thenReturn(Optional.of(candidate()));
        assertThrows(BizException.class, () -> service.requireCurrentVersion("candidate-id", 1));
        verifyNoInteractions(reviews);
    }

    @Test
    void foreignTenantVersionCannotBeFrozenOrPublished() {
        TenantContext.set("other-tenant");
        assertThrows(BizException.class, () -> service.requireCurrentVersion("candidate-id", 2));
        verify(store).find("other-tenant", "candidate-id");
        verifyNoInteractions(reviews);
    }

    private KnowledgeCandidate candidate() {
        return new KnowledgeCandidate("candidate-id", HASH, 2, KnowledgeCandidate.DRAFT, 3,
            "标题", "正文", "关键词", "content-hash", 42, 100);
    }
}
