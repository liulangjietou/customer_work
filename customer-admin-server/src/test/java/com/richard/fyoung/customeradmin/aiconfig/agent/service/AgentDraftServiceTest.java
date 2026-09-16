package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentDraft;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentDraftMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentDraftServiceTest {
    private final AiAgentDraftMapper mapper = mock(AiAgentDraftMapper.class);
    private final AgentService agents = mock(AgentService.class);
    private final AgentDraftService service = new AgentDraftService(mapper, agents, new ObjectMapper());
    private static final String ID = "ba9c14f0-64e3-43e4-928f-ef3d74db4aa1";

    @Test
    void incompleteDraftShouldRoundTripWithoutRuntimeWritesOrModelCalls() {
        AgentSaveRequest configuration = configuration("未完成的配送助手");
        AgentDraftSaveRequest request = new AgentDraftSaveRequest(0L, null, null, configuration);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            assertFalse(factory.getValidator().validate(configuration).isEmpty(), "正式保存仍要求模型与编码");
        }
        var result = service.save(ID, 7L, request);
        assertEquals(configuration, result.configuration());
        assertEquals(1, result.version());
        var saved = ArgumentCaptor.forClass(AiAgentDraft.class);
        verify(mapper).insert(saved.capture());
        assertEquals(7L, saved.getValue().getOwnerUserId());
        verifyNoInteractions(agents);
    }

    @Test
    void staleDraftVersionShouldNotOverwriteOrDeleteNewerContent() {
        AiAgentDraft existing = new AiAgentDraft();
        existing.setVersion(4L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        assertEquals(ResultCode.CONFIG_EDIT_CONFLICT, assertThrows(BizException.class,
            () -> service.save(ID, 7L, new AgentDraftSaveRequest(3L, null, null, configuration("旧内容"))))
            .getResultCode());
        assertEquals(ResultCode.CONFIG_EDIT_CONFLICT, assertThrows(BizException.class,
            () -> service.delete(ID, 7L, 3L)).getResultCode());
    }

    @Test
    void restoredDraftCannotChangeTargetOrBaseRevision() {
        AiAgentDraft existing = new AiAgentDraft();
        existing.setAgentId(8L);
        existing.setBaseRevision(2L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        assertThrows(BizException.class, () -> service.save(ID, 7L,
            new AgentDraftSaveRequest(1L, 8L, 3L, configuration("重定向草稿"))));
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void unknownDraftShouldNotRevealContents() {
        assertEquals(ResultCode.RESOURCE_NOT_FOUND,
            assertThrows(BizException.class, () -> service.get(ID, 8L)).getResultCode());
    }

    @Test
    void draftShouldRejectOversizeAndUnpairedTargetBeforeWriting() {
        assertThrows(BizException.class, () -> service.save(ID, 7L,
            new AgentDraftSaveRequest(0L, 8L, null, configuration("没有原版本"))));
        assertThrows(BizException.class, () -> service.save(ID, 7L,
            new AgentDraftSaveRequest(0L, null, null, configuration("长内容".repeat(30_000)))));
        verify(mapper, never()).insert(any(AiAgentDraft.class));
    }

    static AgentSaveRequest configuration(String name) {
        return new AgentSaveRequest(name, "", null, List.of(), List.of(), List.of(), List.of(),
            "依据发布知识回答😀", List.of("chat"), "", 0, List.of(), null, null, null, null, null, List.of());
    }
}
