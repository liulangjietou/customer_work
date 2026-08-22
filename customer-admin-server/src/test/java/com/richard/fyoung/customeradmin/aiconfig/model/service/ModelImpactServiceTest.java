package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactItemVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.ModelImpactMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模型影响汇总与删除/禁用共用阻断规则测试。 */
class ModelImpactServiceTest {

    @Test
    void query_shouldAggregateExistingAgentChannelTaskAndVersionReferences() {
        ModelImpactMapper mapper = mock(ModelImpactMapper.class);
        when(mapper.findImpacts(9L, "tenant-a")).thenReturn(List.of(
            item("AGENT", true), item("CHANNEL_BINDING", true),
            item("SCHEDULED_TASK", false), item("CONFIG_VERSION", false)));
        ModelImpactService service = new ModelImpactService(mapper);

        ModelImpactVO impact = service.query(model(), "tenant-a", "DELETE");

        assertEquals(4, impact.totalCount());
        assertEquals(2, impact.blockerCount());
        assertFalse(impact.allowed());
        assertEquals(1L, impact.countsByType().get("AGENT"));
        assertEquals(1L, impact.countsByType().get("CONFIG_VERSION"));
        verify(mapper).findImpacts(9L, "tenant-a");
    }

    @Test
    void requireAllowed_shouldUseSamePreflightForDisableAndReturnResourceInUse() {
        ModelImpactMapper mapper = mock(ModelImpactMapper.class);
        when(mapper.findImpacts(9L, null)).thenReturn(List.of(item("CHANNEL_ROBOT", true)));
        ModelImpactService service = new ModelImpactService(mapper);

        BizException exception = assertThrows(BizException.class,
            () -> service.requireAllowed(model(), null, "DISABLE"));

        assertEquals(ResultCode.RESOURCE_IN_USE, exception.getResultCode());
        assertTrue(exception.getMessage().contains("禁用"));
        verify(mapper).findImpacts(9L, null);
    }

    @Test
    void requireAllowed_shouldDescribeCredentialRotationBlockerAccurately() {
        ModelImpactMapper mapper = mock(ModelImpactMapper.class);
        when(mapper.findImpacts(9L, "tenant-a"))
            .thenReturn(List.of(item("MODEL_EXPERIMENT", true)));
        ModelImpactService service = new ModelImpactService(mapper);

        BizException exception = assertThrows(BizException.class,
            () -> service.requireAllowed(model(), "tenant-a", "ROTATE"));

        assertEquals(ResultCode.RESOURCE_IN_USE, exception.getResultCode());
        assertTrue(exception.getMessage().contains("轮换凭据"));
    }

    private AiModelConfig model() {
        AiModelConfig model = new AiModelConfig();
        model.setId(9L);
        return model;
    }

    private ModelImpactItemVO item(String type, boolean blocking) {
        ModelImpactItemVO item = new ModelImpactItemVO();
        item.setTenantId("tenant-a");
        item.setResourceType(type);
        item.setBlocking(blocking);
        return item;
    }
}
