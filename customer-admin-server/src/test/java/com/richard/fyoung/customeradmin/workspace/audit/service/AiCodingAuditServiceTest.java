package com.richard.fyoung.customeradmin.workspace.audit.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation;
import com.richard.fyoung.customeradmin.workspace.audit.dto.AiCodingAuditQuery;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.mapper.AiCodingAuditLogMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.model.ChatUsage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiCodingAuditService} 单测：审计条目构建（无登录上下文防御）、结果补全与错误码解析
 * （CompletionException 链 unwrap）、同步包装的异常透传、用量/变更文件写入、分页查询。
 * @author owlzhangfq@gmail.com
 */
class AiCodingAuditServiceTest {

    private AiCodingAuditRecorder recorder;
    private AiCodingAuditLogMapper mapper;
    private AiCodingAuditService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiCodingAuditLog.class);
    }

    @BeforeEach
    void setUp() {
        recorder = mock(AiCodingAuditRecorder.class);
        mapper = mock(AiCodingAuditLogMapper.class);
        service = new AiCodingAuditService(recorder, mapper);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ===== begin：条目构建 =====

    @Test
    void begin_shouldFillOperationAndTimer_andLeaveOperatorEmptyWithoutLoginContext() {
        // 单测没有 Sa-Token 上下文——操作人解析必须防御性兜底（留空），不能让审计炸掉业务
        AiCodingAuditLog entry = service.begin(AiCodingOperation.CHAT_STREAM, "coder", "s1");

        assertEquals("CHAT_STREAM", entry.getOperation());
        assertEquals("coder", entry.getAgentCode());
        assertEquals("s1", entry.getSessionId());
        assertTrue(entry.getStartMillis() > 0);
        assertNull(entry.getUserId());
        assertNull(entry.getUsername());
    }

    @Test
    void begin_shouldCaptureTenantForDeferredPersistence() {
        TenantContext.set("tenant-a");

        AiCodingAuditLog entry = service.begin(AiCodingOperation.GIT_DIFF_SUMMARY, "coder", "s1");
        TenantContext.clear();
        service.finish(entry, (String) null);

        ArgumentCaptor<AiCodingAuditLog> captor = ArgumentCaptor.forClass(AiCodingAuditLog.class);
        verify(recorder).persist(captor.capture());
        assertEquals("tenant-a", captor.getValue().getTenantContextId(),
            "审计结束时请求线程可能已释放，必须使用 begin 时的租户快照");
    }

    @Test
    void tenantContextSnapshot_shouldNotMapToDatabaseColumn() {
        assertTrue(TableInfoHelper.getTableInfo(AiCodingAuditLog.class).getFieldList().stream()
            .noneMatch(field -> "tenantContextId".equals(field.getProperty())),
            "租户快照只用于进程内传播，不能生成额外 SQL 列");
    }

    @Test
    void tenantContextSnapshot_shouldNotSerializeToAuditApiResponse() throws Exception {
        AiCodingAuditLog entry = new AiCodingAuditLog();
        entry.setTenantContextId("tenant-a");

        String json = new ObjectMapper().writeValueAsString(entry);

        assertFalse(json.contains("tenantContextId"),
            "租户快照是内部传播字段，不能改变审计分页 API 契约");
    }

    // ===== finish：结果补全与落库 =====

    @Test
    void finish_shouldMarkSuccess_whenErrorCodeIsNull() {
        AiCodingAuditLog entry = service.begin(AiCodingOperation.FILE_SAVE, "coder", "s1");
        service.finish(entry, (String) null);

        ArgumentCaptor<AiCodingAuditLog> captor = ArgumentCaptor.forClass(AiCodingAuditLog.class);
        verify(recorder).persist(captor.capture());
        assertEquals(AiCodingAuditLog.RESULT_SUCCESS, captor.getValue().getResult());
        assertNull(captor.getValue().getErrorCode());
        assertTrue(captor.getValue().getDurationMs() >= 0);
    }

    @Test
    void finish_shouldMarkFailureWithErrorCode() {
        AiCodingAuditLog entry = service.begin(AiCodingOperation.CHAT_STREAM, "coder", "s1");
        service.finish(entry, "VIBECODING_STREAM_CANCEL");

        ArgumentCaptor<AiCodingAuditLog> captor = ArgumentCaptor.forClass(AiCodingAuditLog.class);
        verify(recorder).persist(captor.capture());
        assertEquals(AiCodingAuditLog.RESULT_FAILURE, captor.getValue().getResult());
        assertEquals("VIBECODING_STREAM_CANCEL", captor.getValue().getErrorCode());
    }

    @Test
    void finish_shouldNotPropagateAsyncTaskSubmissionFailure() {
        AiCodingAuditLog entry = service.begin(AiCodingOperation.GIT_DIFF_SUMMARY, "coder", "s1");
        doThrow(new IllegalStateException("executor rejected")).when(recorder).persist(entry);

        assertDoesNotThrow(() -> service.finish(entry, (String) null),
            "审计线程池拒绝任务不能覆盖 Git 助手的业务结果");
    }

    @Test
    void finish_shouldUnwrapCompletionException_andUseBizResultCodeName() {
        // CompletableFuture 链路（Git 助手）抛出的业务异常被 CompletionException 包装，
        // 错误码必须沿 cause 链取到真实的 ResultCode 枚举名
        AiCodingAuditLog entry = service.begin(AiCodingOperation.COMMIT_MESSAGE, "coder", "s1");
        service.finish(entry, new CompletionException(new BizException(ResultCode.NO_FILE_CHANGES)));

        ArgumentCaptor<AiCodingAuditLog> captor = ArgumentCaptor.forClass(AiCodingAuditLog.class);
        verify(recorder).persist(captor.capture());
        assertEquals("NO_FILE_CHANGES", captor.getValue().getErrorCode());
    }

    @Test
    void finish_shouldFallBackToSystemError_forNonBizException() {
        AiCodingAuditLog entry = service.begin(AiCodingOperation.GIT_DIFF_SUMMARY, "coder", "s1");
        service.finish(entry, new RuntimeException("unexpected"));

        ArgumentCaptor<AiCodingAuditLog> captor = ArgumentCaptor.forClass(AiCodingAuditLog.class);
        verify(recorder).persist(captor.capture());
        assertEquals("SYSTEM_ERROR", captor.getValue().getErrorCode());
    }

    // ===== 用量与变更文件写入 =====

    @Test
    void applyUsage_shouldFillTokenColumns_andKeepNullWhenUsageAbsent() {
        AiCodingAuditLog entry = new AiCodingAuditLog();
        service.applyUsage(entry, null);
        assertNull(entry.getTotalTokens());

        service.applyUsage(entry, new ChatUsage(100, 50, 0.5));
        assertEquals(100, entry.getInputTokens());
        assertEquals(50, entry.getOutputTokens());
        assertEquals(150, entry.getTotalTokens());
    }

    @Test
    void applyChangedFiles_shouldSerializeToJson_andSkipEmptyList() {
        AiCodingAuditLog entry = new AiCodingAuditLog();
        service.applyChangedFiles(entry, List.of());
        assertNull(entry.getChangedFiles());

        service.applyChangedFiles(entry, List.of("src/main/java/Foo.java", "pom.xml"));
        assertEquals("[\"src/main/java/Foo.java\",\"pom.xml\"]", entry.getChangedFiles());
    }

    // ===== 分页查询 =====

    @Test
    void page_shouldQueryWithFilters_andConvertToPageResult() {
        when(mapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<AiCodingAuditLog> page = inv.getArgument(0);
            page.setTotal(1);
            page.setRecords(List.of(new AiCodingAuditLog()));
            return page;
        });

        AiCodingAuditQuery query = new AiCodingAuditQuery();
        query.setKeyword("admin");
        query.setAgentCode("coder");
        query.setSessionId("s1");
        query.setOperation("CHAT_STREAM");
        query.setStatus(1);
        PageResult<AiCodingAuditLog> result = service.page(query);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
    }
}
