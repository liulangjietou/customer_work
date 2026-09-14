package com.richard.fyoung.customeradmin.ops.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customeradmin.ops.service.OpsAdminService;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapStore;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 覆盖 HTTP 默认参数到真实运营 Service 的分区链路；权限由独立拦截器门禁验证。 */
class KnowledgeGapControllerTest {

    /** 旧测试只测排行读分区，未覆盖 /fill 可以绕过候选及评测直接插入正式知识。 */
    @Test
    void legacyFillMustReturnActionableRejectionWithoutWritingFaq() throws Exception {
        var provider = mock(OpsGatewayProvider.class);
        var gateway = mock(OpsGateway.class);
        var mapper = mock(com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper.class);
        when(provider.get()).thenReturn(gateway); when(gateway.knowledgeMapper()).thenReturn(mapper);
        when(mapper.insert(org.mockito.ArgumentMatchers.any(com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO.class)))
            .thenAnswer(call -> { ((com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO) call.getArgument(0)).setId(991L); return 1; });
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeGapController(new OpsAdminService(provider)))
            .setControllerAdvice(new com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ops/knowledge-gap/fill")
            .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content("""
                {"title":"未评测知识","content":"未评测正文","keyword":"关键词","questionHash":"%s"}
                """.formatted("a".repeat(64))))
            .andExpect(jsonPath("$.code").value(30001));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void initialBoardMustReadTheCurrentTenantPartition() throws Exception {
        TenantContext.set("tenant-a");
        var store = mock(KnowledgeGapStore.class);
        var provider = mock(OpsGatewayProvider.class);
        when(provider.get()).thenReturn(new OpsGateway(null, null, null, store, null, null, null, null));
        when(store.topGaps("tenant-a", 50)).thenReturn(List.of(
            KnowledgeGap.firstMiss("怎样申请发票", "tenant-a", 1L)));
        var mvc = MockMvcBuilders.standaloneSetup(
            new KnowledgeGapController(new OpsAdminService(provider))).build();

        mvc.perform(get("/api/ops/knowledge-gap/top").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.data[0].question").value("怎样申请发票"));
        verify(store).topGaps("tenant-a", 50);
    }
}
