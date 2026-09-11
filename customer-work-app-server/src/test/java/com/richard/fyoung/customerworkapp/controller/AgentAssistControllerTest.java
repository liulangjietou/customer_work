package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.assist.AgentAssistService;
import com.richard.fyoung.customerwork.capability.assist.ConversationSummary;
import com.richard.fyoung.customerwork.capability.assist.ConversationSummaryService;
import com.richard.fyoung.customerwork.capability.quality.QualityFeedbackRecorder;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 服务凭证入口的历史读取与线程上下文契约，不代替 Admin 工单权限验收。 */
class AgentAssistControllerTest {

    private final ConversationSummaryService summaries = mock(ConversationSummaryService.class);
    private final AgentAssistController controller = new AgentAssistController(new AgentAssistService(),
        summaries, mock(QualityFeedbackRecorder.class));

    @Test
    void summary_shouldRestoreAuthenticatedContextInBlockingWorker() {
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<QuotaSubject> subject = new AtomicReference<>();
        QuotaSubject expected = QuotaSubject.apiKey("test-service-key");
        when(summaries.summarize("session-a")).thenAnswer(invocation -> {
            tenant.set(TenantContext.get());
            subject.set(QuotaSubjectContext.get());
            return new ConversationSummary("原文整理", "用户咨询", null, List.of(), List.of(),
                "待核对", "请补充问题", false);
        });

        controller.summary("session-a").contextWrite(context -> context
            .put(TenantContextThreadLocalAccessor.KEY, "tenant-a")
            .put(QuotaSubjectContextThreadLocalAccessor.KEY, expected)).block();

        assertEquals("tenant-a", tenant.get());
        assertEquals(expected, subject.get());
        assertFalse(TenantContext.isPresent());
        assertFalse(QuotaSubjectContext.isPresent());
    }

    @Test
    void summary_shouldReturnServerErrorWhenHistoryCannotBeRead() {
        when(summaries.summarize("session-a")).thenThrow(new IllegalStateException("storage unavailable"));
        WebTestClient.bindToController(controller).build().get()
            .uri("/api/customer/assist/summary?sessionId=session-a").exchange()
            .expectStatus().is5xxServerError();
    }
}
