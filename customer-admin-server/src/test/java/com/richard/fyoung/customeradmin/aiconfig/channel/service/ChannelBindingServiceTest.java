package com.richard.fyoung.customeradmin.aiconfig.channel.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.dto.ChannelBindingSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelBindingService} 单测：channelCode 唯一性校验（create 全局查重 / update 排除自身查重）。
 * @author owlzhangfq@gmail.com
 */
class ChannelBindingServiceTest {

    private AiChannelBindingMapper bindingMapper;
    private AiAgentMapper agentMapper;
    private ChannelBindingService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(AiChannelBindingMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        service = new ChannelBindingService(bindingMapper, agentMapper, publisher);
        when(agentMapper.selectById(1L)).thenReturn(new AiAgent());
    }

    private AiChannelBinding binding(Long id, String code) {
        AiChannelBinding b = new AiChannelBinding();
        b.setId(id);
        b.setChannelCode(code);
        b.setAgentId(1L);
        b.setStatus(1);
        return b;
    }

    @Test
    void createRejectsDuplicateChannelCode() {
        when(bindingMapper.exists(any())).thenReturn(true);
        BizException e = assertThrows(BizException.class,
            () -> service.create(new ChannelBindingSaveRequest("web", 1L, 1)));
        assertEquals(ResultCode.RESOURCE_DUPLICATE, e.getResultCode());
    }

    @Test
    void updateRejectsChannelCodeUsedByOtherBinding() {
        when(bindingMapper.selectById(10L)).thenReturn(binding(10L, "web"));
        // 排除自身后仍命中 → 别的绑定占用了新 code
        when(bindingMapper.exists(any())).thenReturn(true);
        BizException e = assertThrows(BizException.class,
            () -> service.update(10L, new ChannelBindingSaveRequest("wechat", 1L, 1)));
        assertEquals(ResultCode.RESOURCE_DUPLICATE, e.getResultCode());
    }

    @Test
    void updateAllowsKeepingOwnChannelCode() {
        when(bindingMapper.selectById(10L)).thenReturn(binding(10L, "web"));
        // 排除自身查重：exists 用 ne(id) 条件后未命中
        when(bindingMapper.exists(any())).thenReturn(false);
        service.update(10L, new ChannelBindingSaveRequest("web", 1L, 1));
        verify(bindingMapper).updateById(any(AiChannelBinding.class));
    }
}
