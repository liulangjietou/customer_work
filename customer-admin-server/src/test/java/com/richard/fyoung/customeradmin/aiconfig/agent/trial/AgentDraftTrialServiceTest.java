package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 外部执行与持久受理分界，特别覆盖响应丢失时不得重放和不得误记模型失败。 */
class AgentDraftTrialServiceTest {
    private final AgentDraftTrialStore store = mock(AgentDraftTrialStore.class);
    private final AgentDraftTrialFreezer freezer = mock(AgentDraftTrialFreezer.class);
    private final AgentDraftTrialRunner runner = mock(AgentDraftTrialRunner.class);
    private final SubjectQuotaGuard quota = mock(SubjectQuotaGuard.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentDraftTrialService service = new AgentDraftTrialService(store, freezer, runner, quota, json);
    private final AgentDraftVO draft = new AgentDraftVO("draft", null, null, "草稿", 3L, 10L, null);
    private final AgentDraftTrialRequest request = new AgentDraftTrialRequest(3L, "查询配送时间");
    private final AgentDraftTrialScope scope = new AgentDraftTrialScope("tenant-a", 7L, "draft", "trial");
    private final FrozenAgentDraft snapshot = new FrozenAgentDraft(null, null,
        new com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest("试用", "trial-agent", 1L,
            List.of(), List.of(), List.of(), List.of(), "规则", List.of("chat"), null, 1,
            List.of(), 3, null, null, null, null, List.of()),
        List.of(), null, List.of(), List.of(), List.of(), List.of("只读范围"));
    private final AgentInvocationIdentity identity = new AgentInvocationIdentity("tenant-a",
        QuotaSubjectType.ADMIN_USER, "7", true);

    @BeforeEach
    void setup() throws Exception {
        TenantContext.set("tenant-a");
        when(freezer.freeze(draft)).thenReturn(snapshot);
        when(freezer.fingerprint(snapshot)).thenReturn("config");
        when(freezer.encode(snapshot)).thenReturn(json.writeValueAsString(snapshot));
        when(store.accept(any())).thenReturn(true);
        when(quota.isEnabled()).thenReturn(true);
        when(quota.check(any(), any())).thenReturn(SubjectQuotaDecision.allow());
        when(runner.run(any(), any(), any())).thenReturn(new AgentDraftTrialRunner.TrialResult(
            "配送回答", false, 100L, List.of(), snapshot.restrictions()));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void lostSuccessResponseRecoversWithoutASecondModelCallOrQuotaCharge() throws Exception {
        var success = record(AgentDraftTrialPhase.SUCCEEDED, null);
        when(store.find(scope)).thenReturn(Optional.empty(), Optional.of(success));
        var first = service.start(draft, 7L, "trial", request, identity);
        var recovered = service.start(draft, 7L, "trial", request, identity);
        assertEquals(first, recovered);
        assertEquals(AgentDraftTrialMatch.MATCH, recovered.configurationMatch());
        assertEquals("配送回答", recovered.result().answer());
        verify(runner).run(eq(snapshot), any(), eq(identity.forInvocation("admin", "trial", "trial-agent")));
        verify(quota).recordRequest(QuotaSubject.adminUser("7"));
        verify(store).accept(any());
    }

    @Test
    void duplicateAcceptanceLoserOnlyReadsTheWinnersReceipt() throws Exception {
        var running = record(AgentDraftTrialPhase.RUNNING, null);
        when(store.find(scope)).thenReturn(Optional.empty(), Optional.of(running));
        when(store.accept(any())).thenReturn(false);
        assertEquals(AgentDraftTrialPhase.RUNNING, service.start(draft, 7L, "trial", request, identity).phase());
        verifyNoInteractions(runner, quota);
    }

    @Test
    void changedRequestIdContentIsAConflictBeforeAnyExecution() throws Exception {
        when(store.find(scope)).thenReturn(Optional.of(record(AgentDraftTrialPhase.RUNNING, null)));
        assertThrows(BizException.class, () -> service.start(draft, 7L, "trial",
            new AgentDraftTrialRequest(3L, "另一个问题"), identity));
        verifyNoInteractions(runner, quota);
        verify(store, never()).accept(any());
    }

    @Test
    void oldDraftVersionCannotBeAcceptedAsTheCurrentConfiguration() {
        assertThrows(BizException.class, () -> service.start(draft, 7L, "trial",
            new AgentDraftTrialRequest(2L, request.input()), identity));
        verifyNoInteractions(runner, quota);
        verify(freezer, never()).freeze(any());
        verify(store, never()).accept(any());
    }

    @Test
    void failedAcceptanceNeverStartsOrChargesTheModel() {
        when(store.accept(any())).thenThrow(new DataAccessResourceFailureException("write unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.start(draft, 7L, "trial", request, identity));
        verifyNoInteractions(runner, quota);
    }

    @Test
    void uncertainTerminalWriteMustNotBeChangedIntoAnExecutionFailure() {
        when(store.succeed(eq(scope), anyString(), anyLong()))
            .thenThrow(new DataAccessResourceFailureException("response lost after possible commit"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.start(draft, 7L, "trial", request, identity));
        verify(store, never()).fail(any(), any(), anyLong());
    }

    @Test
    void knownTimeoutIsPersistedWithoutExposingTheUnderlyingException() throws Exception {
        when(runner.run(any(), any(), any())).thenThrow(new IllegalStateException("secret vendor details",
            new TimeoutException("vendor timeout")));
        when(store.find(scope)).thenReturn(Optional.empty(), Optional.of(record(AgentDraftTrialPhase.FAILED, "TRIAL_TIMEOUT")));
        var view = service.start(draft, 7L, "trial", request, identity);
        assertEquals("TRIAL_TIMEOUT", view.errorCode());
        verify(store).fail(eq(scope), eq("TRIAL_TIMEOUT"), anyLong());
        assertTrue(!json.writeValueAsString(view).contains("secret vendor details"));
    }

    @Test
    void quotaFailureDoesNotRunAndRecoveryDoesNotCharge() throws Exception {
        when(quota.check(any(), any())).thenThrow(new BizException(ResultCode.QUOTA_EXCEEDED));
        when(store.find(scope)).thenReturn(Optional.empty(), Optional.of(record(AgentDraftTrialPhase.FAILED, "TRIAL_QUOTA_EXCEEDED")));
        assertEquals("TRIAL_QUOTA_EXCEEDED", service.start(draft, 7L, "trial", request, identity).errorCode());
        verifyNoInteractions(runner);
        verify(quota, never()).recordRequest(any());
    }

    @Test
    void unavailableResourcesDoNotTurnHistoricalResultsIntoCurrentEvidence() throws Exception {
        when(store.find(scope)).thenReturn(Optional.of(record(AgentDraftTrialPhase.SUCCEEDED, null)));
        when(freezer.freeze(draft)).thenThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND));
        var view = service.get(draft, 7L, "trial");
        assertEquals(AgentDraftTrialMatch.UNAVAILABLE, view.configurationMatch());
        assertEquals("配送回答", view.result().answer());
        verifyNoInteractions(runner, quota);
    }

    private AgentDraftTrialRecord record(AgentDraftTrialPhase phase, String error) throws Exception {
        String result = phase == AgentDraftTrialPhase.SUCCEEDED ? json.writeValueAsString(
            new AgentDraftTrialRunner.TrialResult("配送回答", false, 100L, List.of(), snapshot.restrictions())) : null;
        return new AgentDraftTrialRecord(scope, 3L, request.input(), AgentDraftTrialRecord.fingerprint(scope, request),
            "config", json.writeValueAsString(snapshot), phase, result, error,
            System.currentTimeMillis(), System.currentTimeMillis() + 30000L, null);
    }
}
