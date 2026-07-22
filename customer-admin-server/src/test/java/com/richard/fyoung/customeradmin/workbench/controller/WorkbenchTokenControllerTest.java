package com.richard.fyoung.customeradmin.workbench.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreateRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreatedVO;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenVO;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkbenchTokenController} 单测：各端点绑定当前登录用户并转发到 service。
 * 用 {@link MockedStatic} 模拟 Sa-Token 当前用户。
 * @author owlzhangfq@gmail.com
 */
class WorkbenchTokenControllerTest {

    private static final long USER_ID = 100L;

    private final WorkbenchTokenService service = mock(WorkbenchTokenService.class);
    private final WorkbenchTokenController controller = new WorkbenchTokenController(service);

    @Test
    void list_shouldReturnCurrentUserTokens() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            when(service.listByUser(USER_ID)).thenReturn(List.of(new WorkbenchTokenVO()));

            Result<List<WorkbenchTokenVO>> result = controller.list();

            assertEquals(0, result.getCode());
            assertEquals(1, result.getData().size());
        }
    }

    @Test
    void create_shouldSignForCurrentUser() {
        WorkbenchTokenCreateRequest request = new WorkbenchTokenCreateRequest("脚本用", 30);
        WorkbenchTokenCreatedVO created = new WorkbenchTokenCreatedVO();
        created.setToken("wbt_xxx");
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            when(service.createToken(eq(USER_ID), eq(request))).thenReturn(created);

            Result<WorkbenchTokenCreatedVO> result = controller.create(request);

            assertEquals("wbt_xxx", result.getData().getToken());
        }
    }

    @Test
    void revoke_shouldDelegateWithCurrentUser() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            controller.revoke(5L);

            verify(service).revoke(USER_ID, 5L);
        }
    }
}
