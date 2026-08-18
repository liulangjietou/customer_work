package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RefactorTask;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2 任务编排单测：诊断复用主链路，重构必须先真实挂起确认。 */
class AiCodingTaskServiceTest {

    private final AdminSandboxProperties properties = new AdminSandboxProperties();
    private final VibeCodingService vibeCodingService = mock(VibeCodingService.class);
    private final PlanConfirmationService planService = new PlanConfirmationService();
    private final AiCodingAuditService auditService = mock(AiCodingAuditService.class);
    private final AiCodingTaskService service =
        new AiCodingTaskService(properties, vibeCodingService, planService, auditService);

    @Test
    void diagnose_shouldUseDedicatedPromptAndExistingVibeCodingStream() {
        properties.getFeatures().setDiagnosisEnabled(true);
        when(auditService.begin(any(), eq("coder"), eq("s1"))).thenReturn(new AiCodingAuditLog());
        when(vibeCodingService.stream(eq("coder"), eq("s1"), contains("NullPointerException"), eq("auto")))
            .thenReturn(Flux.just(new ChatStreamChunk(ChatNodeKind.ANSWER, "已修复")));
        when(vibeCodingService.listChangedArtifacts("coder", "s1")).thenReturn(List.of("Foo.java"));

        List<ChatStreamChunk> chunks = service.diagnose("coder", "s1", "NullPointerException")
            .collectList().block();

        assertEquals("已修复", chunks.get(0).text());
        verify(vibeCodingService).stream(eq("coder"), eq("s1"), contains("<untrusted_log>"), eq("auto"));
        verify(auditService).applyChangedFiles(any(), eq(List.of("Foo.java")));
    }

    @Test
    void refactor_shouldNotStartMutationUntilExplicitPlanApproved() throws Exception {
        properties.getFeatures().setRefactorEnabled(true);
        properties.getHitl().setConfirmTimeoutSeconds(5);
        when(auditService.begin(any(), eq("coder"), eq("s1"))).thenReturn(new AiCodingAuditLog());
        when(vibeCodingService.listWorkspaceFiles("coder", "s1")).thenReturn(List.of());
        when(vibeCodingService.listChangedArtifacts("coder", "s1")).thenReturn(List.of("A.java"));
        when(vibeCodingService.stream(eq("coder"), eq("s1"), contains("API_MIGRATION"), eq("accept_edits")))
            .thenReturn(Flux.just(new ChatStreamChunk(ChatNodeKind.ANSWER, "重构完成")));
        RefactorTask task = new RefactorTask("s1", RefactorTask.TaskType.API_MIGRATION,
            "替换废弃 API", List.of("A.java"));
        CountDownLatch planArrived = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> planId = new java.util.concurrent.atomic.AtomicReference<>();

        var future = service.refactor("coder", task)
            .doOnNext(chunk -> {
                if (chunk.kind() == ChatNodeKind.PLAN) {
                    try {
                        planId.set(new ObjectMapper().readValue(chunk.text(), PlanEvent.class).planId());
                        planArrived.countDown();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            })
            .collectList().toFuture();

        assertTrue(planArrived.await(2, TimeUnit.SECONDS));
        verify(vibeCodingService, never()).stream(eq("coder"), eq("s1"), any(), eq("accept_edits"));
        assertTrue(planService.confirm("coder", "s1", planId.get(), true));
        List<ChatStreamChunk> chunks = future.get(3, TimeUnit.SECONDS);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.kind() == ChatNodeKind.PLAN_RESULT));
        assertTrue(chunks.stream().anyMatch(chunk -> "重构完成".equals(chunk.text())));
        verify(vibeCodingService).stream(eq("coder"), eq("s1"), contains("API_MIGRATION"), eq("accept_edits"));
    }

    @Test
    void refactorRejected_shouldCompleteWithoutCallingMutationStream() throws Exception {
        properties.getFeatures().setRefactorEnabled(true);
        properties.getHitl().setConfirmTimeoutSeconds(5);
        when(auditService.begin(any(), eq("coder"), eq("s1"))).thenReturn(new AiCodingAuditLog());
        when(vibeCodingService.listWorkspaceFiles("coder", "s1")).thenReturn(List.of());
        when(vibeCodingService.listChangedArtifacts("coder", "s1")).thenReturn(List.of());
        RefactorTask task = new RefactorTask("s1", RefactorTask.TaskType.REPLACE, "替换异常类", List.of());
        CountDownLatch planArrived = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> planId = new java.util.concurrent.atomic.AtomicReference<>();
        var future = service.refactor("coder", task).doOnNext(chunk -> {
            if (chunk.kind() == ChatNodeKind.PLAN) {
                try {
                    planId.set(new ObjectMapper().readValue(chunk.text(), PlanEvent.class).planId());
                    planArrived.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }).collectList().toFuture();

        assertTrue(planArrived.await(2, TimeUnit.SECONDS));
        assertTrue(planService.confirm("coder", "s1", planId.get(), false));
        List<ChatStreamChunk> chunks = future.get(3, TimeUnit.SECONDS);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("未执行")));
        verify(vibeCodingService, never()).stream(eq("coder"), eq("s1"), any(), eq("accept_edits"));
    }
}
