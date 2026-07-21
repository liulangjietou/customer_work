package com.richard.fyoung.customeradmin.message.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.message.dto.SiteMessageVO;
import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SiteMessageController} 单测：当前用户统一取自 Sa-Token，接口透传给 service 并用 {@link Result} 封装。
 * @author owlzhangfq@gmail.com
 */
class SiteMessageControllerTest {

    private static final long CURRENT_USER = 7L;

    private final SiteMessageService service = mock(SiteMessageService.class);
    private final SiteMessageController controller = new SiteMessageController(service);

    @Test
    void page_shouldQueryCurrentUserWithReadFlag() {
        PageResult<SiteMessageVO> pageResult = new PageResult<>();
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);
            when(service.page(CURRENT_USER, 0, 2, 20)).thenReturn(pageResult);

            Result<PageResult<SiteMessageVO>> result = controller.page(2, 20, 0);

            assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
            assertEquals(pageResult, result.getData());
            verify(service).page(CURRENT_USER, 0, 2, 20);
        }
    }

    @Test
    void unreadCount_shouldReturnCurrentUserCount() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);
            when(service.unreadCount(CURRENT_USER)).thenReturn(5L);

            Result<Long> result = controller.unreadCount();

            assertEquals(5L, result.getData());
        }
    }

    @Test
    void read_shouldDelegateWithCurrentUser() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);

            controller.read(11L);

            verify(service).markRead(eq(11L), eq(CURRENT_USER));
        }
    }

    @Test
    void readAll_shouldDelegateWithCurrentUser() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);

            controller.readAll();

            verify(service).markAllRead(CURRENT_USER);
        }
    }
}
