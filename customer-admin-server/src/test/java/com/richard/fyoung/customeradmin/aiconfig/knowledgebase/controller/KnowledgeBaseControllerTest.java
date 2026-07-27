package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.controller;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseOptionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseTestResult;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseService;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeBaseController} 单测：各端点薄委托 Service，并用统一 {@link Result} 包裹（code=0）。
 * 鉴权注解由 Sa-Token 拦截，非单测范畴。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeBaseControllerTest {

    private KnowledgeBaseService service;
    private KnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeBaseService.class);
        controller = new KnowledgeBaseController(service);
    }

    @Test
    void page_shouldWrapServiceResult() {
        PageResult<KnowledgeBaseVO> pageResult = new PageResult<>();
        PageQuery query = new PageQuery();
        when(service.page(query)).thenReturn(pageResult);

        Result<PageResult<KnowledgeBaseVO>> result = controller.page(query);

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertSame(pageResult, result.getData());
    }

    @Test
    void get_shouldWrapServiceResult() {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        when(service.get(1L)).thenReturn(vo);

        assertSame(vo, controller.get(1L).getData());
    }

    @Test
    void options_shouldWrapServiceResult() {
        List<KnowledgeBaseOptionVO> options = List.of(new KnowledgeBaseOptionVO(1L, "产品知识库"));
        when(service.options()).thenReturn(options);

        assertSame(options, controller.options().getData());
    }

    @Test
    void create_shouldDelegateToService() {
        KnowledgeBaseSaveRequest request = new KnowledgeBaseSaveRequest("产品知识库", "http://localhost:20002",
            "app_1", "sk-1", null, null, 5, BigDecimal.ZERO, 1, null);

        assertEquals(ResultCode.SUCCESS.getCode(), controller.create(request).getCode());
        verify(service).create(request);
    }

    @Test
    void update_shouldDelegateToServiceWithPathId() {
        KnowledgeBaseSaveRequest request = new KnowledgeBaseSaveRequest("产品知识库", "http://localhost:20002",
            "app_1", "", null, null, 5, BigDecimal.ZERO, 1, null);

        assertEquals(ResultCode.SUCCESS.getCode(), controller.update(7L, request).getCode());
        verify(service).update(7L, request);
    }

    @Test
    void delete_shouldDelegateToService() {
        assertEquals(ResultCode.SUCCESS.getCode(), controller.delete(7L).getCode());
        verify(service).delete(7L);
    }

    @Test
    void updateStatus_shouldDelegateToService() {
        assertEquals(ResultCode.SUCCESS.getCode(), controller.updateStatus(7L, 0).getCode());
        verify(service).updateStatus(7L, 0);
    }

    @Test
    void testConnectivity_shouldWrapAsyncServiceResult() throws Exception {
        KnowledgeBaseTestResult testResult = KnowledgeBaseTestResult.success(3);
        when(service.testConnectivity(7L)).thenReturn(CompletableFuture.completedFuture(testResult));

        Result<KnowledgeBaseTestResult> result = controller.testConnectivity(7L).get();

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertSame(testResult, result.getData());
    }
}
