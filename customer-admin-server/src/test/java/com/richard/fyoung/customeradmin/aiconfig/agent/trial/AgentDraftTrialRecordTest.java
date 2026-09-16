package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentDraftTrialRecordTest {
    private static final AgentDraftTrialScope SCOPE =
        new AgentDraftTrialScope("tenant-a", 7L, "draft-id", "trial-id");
    private static final AgentDraftTrialRequest REQUEST = new AgentDraftTrialRequest(3L, "查询配送时间");

    @Test
    void sameRequestCanBeRecoveredButChangedVersionOrInputCannotBeReplayed() {
        var row = record(AgentDraftTrialPhase.RUNNING);
        assertTrue(row.matches(REQUEST));
        assertFalse(row.matches(new AgentDraftTrialRequest(4L, REQUEST.input())));
        assertFalse(row.matches(new AgentDraftTrialRequest(3L, "取消订单")));
    }

    @Test
    void deadlineBoundaryAndFinalStatesRejectLateCompletion() {
        var running = record(AgentDraftTrialPhase.RUNNING);
        assertTrue(running.canRecordSuccess(1999L));
        assertFalse(running.expired(1999L));
        assertFalse(running.canRecordSuccess(2000L));
        assertTrue(running.expired(2000L));
        assertTrue(running.canRecordFailure());
        for (var phase : AgentDraftTrialPhase.values()) {
            if (!phase.terminal()) continue;
            assertFalse(record(phase).canRecordSuccess(1500L));
            assertFalse(record(phase).canRecordFailure());
            assertFalse(record(phase).expired(3000L));
        }
    }

    private AgentDraftTrialRecord record(AgentDraftTrialPhase phase) {
        return new AgentDraftTrialRecord(SCOPE, 3L, REQUEST.input(),
            AgentDraftTrialRecord.fingerprint(SCOPE, REQUEST), "configuration-hash", "{}", phase,
            null, null, 1000L, 2000L, null);
    }
}
