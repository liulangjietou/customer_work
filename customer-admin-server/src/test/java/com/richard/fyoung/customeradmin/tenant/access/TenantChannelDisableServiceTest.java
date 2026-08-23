package com.richard.fyoung.customeradmin.tenant.access;

import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantChannelDisableServiceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void offboarding_shouldDisableBothChannelKindsInsideTargetTenantAndRestoreContext() {
        AiChannelBindingMapper bindingMapper = mock(AiChannelBindingMapper.class);
        AiChannelRobotMapper robotMapper = mock(AiChannelRobotMapper.class);
        when(bindingMapper.update(isNull(), any())).thenAnswer(invocation -> {
            assertEquals("acme", TenantContext.get());
            return 2;
        });
        when(robotMapper.update(isNull(), any())).thenAnswer(invocation -> {
            assertEquals("acme", TenantContext.get());
            return 3;
        });
        TenantContext.set("default");

        int disabled = new TenantChannelDisableService(bindingMapper, robotMapper)
            .disableForOffboarding("acme");

        assertEquals(5, disabled);
        assertEquals("default", TenantContext.get(), "完成后必须恢复调用方租户上下文");
    }
}
