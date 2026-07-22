package com.richard.fyoung.customeradmin.workbench.controller;

import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteSaveRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteVO;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchSiteService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkbenchSiteController} 单测：各端点透传给 service 并用 {@link Result} 封装。
 * @author owlzhangfq@gmail.com
 */
class WorkbenchSiteControllerTest {

    private final WorkbenchSiteService service = mock(WorkbenchSiteService.class);
    private final WorkbenchSiteController controller = new WorkbenchSiteController(service);

    private WorkbenchSiteSaveRequest request() {
        return new WorkbenchSiteSaveRequest("Gitlab", "git", "https://git.internal", "admin", "pwd", "备注", true,
            null, null, null, null, null, null, null);
    }

    @Test
    void page_shouldReturnServiceResult() {
        PageResult<WorkbenchSiteVO> pageResult = new PageResult<>();
        PageQuery query = new PageQuery();
        when(service.page(query)).thenReturn(pageResult);

        Result<PageResult<WorkbenchSiteVO>> result = controller.page(query);

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertEquals(pageResult, result.getData());
        verify(service).page(query);
    }

    @Test
    void create_shouldDelegateToService() {
        WorkbenchSiteSaveRequest request = request();

        Result<Void> result = controller.create(request);

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        verify(service).create(request);
    }

    @Test
    void update_shouldDelegateWithId() {
        WorkbenchSiteSaveRequest request = request();

        controller.update(5L, request);

        verify(service).update(eq(5L), any(WorkbenchSiteSaveRequest.class));
    }

    @Test
    void delete_shouldDelegateWithId() {
        controller.delete(5L);

        verify(service).delete(5L);
    }

    @Test
    void secret_shouldReturnDecryptedPassword() {
        when(service.getSecret(5L)).thenReturn("plain-pass");

        Result<String> result = controller.secret(5L);

        assertEquals("plain-pass", result.getData());
        verify(service).getSecret(5L);
    }
}
