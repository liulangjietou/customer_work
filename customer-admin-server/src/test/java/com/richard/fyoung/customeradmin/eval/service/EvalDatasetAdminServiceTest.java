package com.richard.fyoung.customeradmin.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.eval.config.EvalGateway;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customeradmin.eval.dto.EvalCaseSaveRequest;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetDiffVO;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetImportRequest;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetRelease;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReviewStatus;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalDatasetReleaseStore;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalDatasetSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalDatasetAdminServiceTest {

    private EvalDatasetAdminService service;

    @BeforeEach
    void setUp() {
        EvalGatewayProvider provider = mock(EvalGatewayProvider.class);
        EvalGateway gateway = new EvalGateway(null, new InMemoryEvalCaseStore(),
            new InMemoryEvalDatasetSnapshotStore(), new InMemoryEvalDatasetReleaseStore());
        when(provider.dataset()).thenReturn(gateway);
        service = new EvalDatasetAdminService(provider, new ObjectMapper());
    }

    @Test
    void shouldGovernWorkingSetCreateReviewedVersionsAndDiff() {
        int seedSize = service.listCases(EvalType.QUALITY).size();
        assertTrue(seedSize > 0);
        assertThrows(BizException.class, () -> service.createCase(EvalType.QUALITY,
            request("q-refund-1", "duplicate", "expected")));

        service.createCase(EvalType.QUALITY, request("q-new", "new input", "new expectation"));
        EvalDatasetRelease first = service.createVersion(EvalType.QUALITY, "quality-v1");
        EvalDatasetRelease approved = service.review(first.releaseId(),
            EvalDatasetReviewStatus.APPROVED, "reviewed");
        assertEquals(EvalDatasetReviewStatus.APPROVED, approved.status());
        assertEquals(seedSize + 1, approved.caseCount());

        service.updateCase(EvalType.QUALITY, "q-new",
            request("q-new", "changed input", "changed expectation"));
        service.createCase(EvalType.QUALITY, request("q-added", "added", "added expectation"));
        EvalDatasetRelease second = service.createVersion(EvalType.QUALITY, "quality-v2");
        EvalDatasetDiffVO diff = service.diff(first.releaseId(), second.releaseId());

        assertEquals(List.of("q-added"), diff.addedCaseIds());
        assertTrue(diff.changedCases().stream().anyMatch(item -> item.caseId().equals("q-new")));
        assertEquals(approved, service.requireApprovedQualityRelease(first.releaseId()));
    }

    @Test
    void importShouldRejectDuplicateIdsBeforeChangingStore() {
        int before = service.listCases(EvalType.INTENT).size();
        EvalCaseSaveRequest item = request("batch-1", "input", "ORDER");
        assertThrows(BizException.class, () -> service.importCases(EvalType.INTENT,
            new EvalDatasetImportRequest(List.of(item, item))));
        assertEquals(before, service.listCases(EvalType.INTENT).size());
    }

    private EvalCaseSaveRequest request(String caseId, String input, String expected) {
        return new EvalCaseSaveRequest(caseId, input, expected, "test", true, null);
    }
}
