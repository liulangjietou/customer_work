package com.richard.fyoung.customeradmin.aiconfig.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentDraftService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AgentDraftControllerTest {
    @Test
    void ownerAndWritePermissionShouldComeFromTrustedLoginAndTarget() {
        var service = mock(AgentDraftService.class);
        var controller = new AgentDraftController(service);
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var create = new AgentDraftSaveRequest(0L, null, null, null);
            var edit = new AgentDraftSaveRequest(1L, 8L, 2L, null);
            controller.save("new-id", create);
            controller.save("edit-id", edit);
            controller.get("get-id");
            controller.delete("delete-id", 3L);
            verify(service).save("new-id", 7L, create);
            verify(service).save("edit-id", 7L, edit);
            verify(service).get("get-id", 7L);
            verify(service).delete("delete-id", 7L, 3L);
            auth.verify(() -> StpUtil.checkPermission("agent:add"));
            auth.verify(() -> StpUtil.checkPermission("agent:edit"));
        }
    }
}
