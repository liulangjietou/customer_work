package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentDraftService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentDraftTrialControllerTest {
    private final AgentDraftService drafts = mock(AgentDraftService.class);
    private final AgentDraftTrialService trials = mock(AgentDraftTrialService.class);
    private final AgentDraftTrialController controller = new AgentDraftTrialController(drafts, trials);
    private final AgentDraftTrialRequest request = new AgentDraftTrialRequest(2L, "问题");

    @AfterEach
    void cleanup() { TenantContext.clear(); AgentInvocationIdentityContext.clear(); }

    @Test
    void entryUsesTheRealLoginOwnerAndTheDraftsActualWritePermission() {
        var draft = new AgentDraftVO("draft", 11L, 1L, "编辑", 2L, 10L, null);
        var identity = new AgentInvocationIdentity("tenant-a", QuotaSubjectType.ADMIN_USER, "7", true);
        TenantContext.set("tenant-a"); AgentInvocationIdentityContext.set(identity);
        when(drafts.get("draft", 7L)).thenReturn(draft);
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            controller.start("draft", "trial", request);
            auth.verify(() -> StpUtil.checkPermission("agent:edit"));
            verify(trials).start(draft, 7L, "trial", request, identity);
        }
    }

    @Test
    void missingOrCaseVariantIdentityCannotExecute() {
        TenantContext.set("tenant-a");
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThrows(BizException.class, () -> controller.start("draft", "trial", request));
            AgentInvocationIdentityContext.set(new AgentInvocationIdentity("Tenant-A", QuotaSubjectType.ADMIN_USER, "7", true));
            assertThrows(BizException.class, () -> controller.start("draft", "trial", request));
            verifyNoInteractions(trials, drafts);
        }
    }

    @Test
    void revokedWritePermissionAlsoPreventsReceiptAndPreviewAccess() {
        when(drafts.get("draft", 7L)).thenReturn(new AgentDraftVO("draft", null, null, "新建", 1L, 0L, null));
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            auth.when(() -> StpUtil.checkPermission("agent:add")).thenThrow(new BizException(ResultCode.FORBIDDEN));
            assertThrows(BizException.class, () -> controller.preview("draft"));
            assertThrows(BizException.class, () -> controller.get("draft", "trial"));
            assertThrows(BizException.class, () -> controller.list("draft"));
            verifyNoInteractions(trials);
        }
    }
}
