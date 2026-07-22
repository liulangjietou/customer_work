package com.richard.fyoung.customeradmin.workbench.controller;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchAgentSiteVO;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchSiteService;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkbenchAgentController} 单测：令牌校验 → 反查凭证 → 审计落库的编排，
 * 以及令牌无效时不查凭证、不审计的短路。全部依赖 mock。
 * @author owlzhangfq@gmail.com
 */
class WorkbenchAgentControllerTest {

    private final WorkbenchTokenService tokenService = mock(WorkbenchTokenService.class);
    private final WorkbenchSiteService siteService = mock(WorkbenchSiteService.class);
    private final OperationLogMapper operationLogMapper = mock(OperationLogMapper.class);
    private final WorkbenchAgentController controller =
        new WorkbenchAgentController(tokenService, siteService, operationLogMapper);

    @Test
    void site_shouldValidateTokenThenReturnCredential_andAudit() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.0.0.9");
        when(tokenService.validate("wbt_ok")).thenReturn(100L);
        WorkbenchAgentSiteVO vo = new WorkbenchAgentSiteVO();
        vo.setAccount("test-user");
        vo.setPassword("test-pass");
        when(siteService.findAgentSiteByHost("wiki.example.com")).thenReturn(vo);

        Result<WorkbenchAgentSiteVO> result = controller.site("wiki.example.com", "wbt_ok", req);

        assertEquals(0, result.getCode());
        assertEquals("test-user", result.getData().getAccount());
        ArgumentCaptor<SysOperationLog> logCaptor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(operationLogMapper).insert((SysOperationLog) logCaptor.capture());
        SysOperationLog log = logCaptor.getValue();
        assertEquals(100L, log.getUserId());
        assertEquals("workbench_site", log.getTarget());
    }

    @Test
    void site_shouldShortCircuit_whenTokenInvalid() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tokenService.validate("bad")).thenThrow(new BizException(ResultCode.UNAUTHORIZED, "令牌无效或已吊销"));

        assertThrows(BizException.class, () -> controller.site("wiki.example.com", "bad", req));

        verify(siteService, never()).findAgentSiteByHost(any());
        verify(operationLogMapper, never()).insert(any(SysOperationLog.class));
    }
}
