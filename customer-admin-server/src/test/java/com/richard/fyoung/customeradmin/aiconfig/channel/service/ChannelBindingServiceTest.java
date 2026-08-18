package com.richard.fyoung.customeradmin.aiconfig.channel.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.dto.ChannelBindingSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    private RuntimePublishTaskMapper publishTaskMapper;
    private ChannelBindingService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(AiChannelBindingMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        publishTaskMapper = mock(RuntimePublishTaskMapper.class);
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        service = new ChannelBindingService(
            bindingMapper, agentMapper, publisher, publishTaskMapper);
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

    @Test
    void createRejectsDifferentAgentSharingGlobalRuntimeDataId() {
        AiAgent anotherAgent = new AiAgent();
        anotherAgent.setId(2L);
        when(agentMapper.selectById(2L)).thenReturn(anotherAgent);
        when(bindingMapper.exists(any())).thenReturn(false, true);

        BizException error = assertThrows(BizException.class,
            () -> service.create(new ChannelBindingSaveRequest("wechat", 2L, 1)));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
    }

    @Test
    void listIncludesLatestReliablePublishStatus() {
        AiChannelBinding binding = binding(10L, "web");
        AiAgent agent = new AiAgent();
        agent.setId(1L);
        agent.setAgentName("客服智能体");
        RuntimePublishTask task = new RuntimePublishTask();
        task.setTargetId(1L);
        task.setSeq(2L);
        task.setStatus("PARTIAL");
        task.setRevision("revision-2");
        task.setLastError("one instance rejected");
        task.setUpdatedAtMs(200L);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent));
        when(publishTaskMapper.selectList(any())).thenReturn(List.of(task));

        var result = service.list();

        assertEquals(1, result.size());
        assertEquals("PARTIAL", result.get(0).getPublishStatus());
        assertEquals("revision-2", result.get(0).getPublishRevision());
        assertEquals("one instance rejected", result.get(0).getPublishLastError());
        assertEquals(200L, result.get(0).getPublishUpdatedAtMs());
    }
}
