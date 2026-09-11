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

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void initialBoardMustReadTheCurrentTenantPartition() throws Exception {
        TenantContext.set("tenant-a");
        var store = mock(KnowledgeGapStore.class);
        var provider = mock(OpsGatewayProvider.class);
        when(provider.get()).thenReturn(new OpsGateway(null, null, null, store, null, null, null));
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
