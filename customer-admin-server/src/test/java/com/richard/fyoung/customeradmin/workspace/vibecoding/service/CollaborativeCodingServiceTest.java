package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.workspace.runtime.AgentWorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.config.AdminCollaborationProperties;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RoleStageEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CollaborativeCodingService} 单测：多角色顺序流水的角色顺序、上下文累积与失败 fast fail 中断。
 * 全程用 PLAN 角色（一次性模型调用，Model 以 Mockito 桩）避免触达真实沙箱链路。
 * @author owlzhangfq@gmail.com
 */
class CollaborativeCodingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AiAgentMapper agentMapper = mock(AiAgentMapper.class);
    private final AdminAgentInstanceFactory agentInstanceFactory = mock(AdminAgentInstanceFactory.class);
    private final AgentWorkspaceManager workspaceManager = mock(AgentWorkspaceManager.class);
    private final VibeCodingService vibeCodingService = mock(VibeCodingService.class);
    private final GitWorkspaceService gitWorkspaceService = mock(GitWorkspaceService.class);
    private final AiCodingAuditService auditService = mock(AiCodingAuditService.class);

    private CollaborativeCodingService newService(AdminCollaborationProperties properties, Model model) {
        AiAgent agent = new AiAgent();
        agent.setAgentCode("demo");
        agent.setCapabilities("vibecoding");
        when(agentMapper.selectOne(any())).thenReturn(agent);
        when(agentInstanceFactory.buildModelForAgent(any())).thenReturn(model);
        when(workspaceManager.resolveSessionWorkspace(any(), any())).thenReturn(Path.of(System.getProperty("java.io.tmpdir")));
        when(gitWorkspaceService.changedFilesAgainstBaseline(any())).thenReturn(List.of());
        when(auditService.begin(any(), any(), any())).thenReturn(new AiCodingAuditLog());
        return new CollaborativeCodingService(agentMapper, properties, agentInstanceFactory,
            vibeCodingService, gitWorkspaceService, auditService, workspaceManager);
    }

    private static AdminCollaborationProperties.Role planRole(String name) {
        return new AdminCollaborationProperties.Role(name, RoleStageEvent.TYPE_PLAN, name + "的系统提示词");
    }

    private static ChatResponse response(String text) {
        ContentBlock block = TextBlock.builder().text(text).build();
        return new ChatResponse(null, List.of(block), null, Map.of(), null);
    }

    private List<RoleStageEvent> collect(Flux<ChatStreamChunk> flux) {
        List<ChatStreamChunk> chunks = flux.collectList().block(Duration.ofSeconds(10));
        assertTrue(chunks != null && !chunks.isEmpty());
        return chunks.stream().map(c -> {
            assertEquals(ChatNodeKind.ROLE_STAGE, c.kind());
            try {
                return objectMapper.readValue(c.text(), RoleStageEvent.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    @Test
    void runsRolesInOrderAndAccumulatesContext() {
        AdminCollaborationProperties properties = new AdminCollaborationProperties();
        properties.setRoles(List.of(planRole("需求分析师"), planRole("架构师")));
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any()))
            .thenReturn(Flux.just(response("分析结论AAA")))
            .thenReturn(Flux.just(response("设计结论BBB")));

        CollaborativeCodingService service = newService(properties, model);
        List<RoleStageEvent> events = collect(service.stream("demo", "s1", "写一个加法接口"));

        List<String> statuses = events.stream().map(RoleStageEvent::status).collect(Collectors.toList());
        assertEquals(List.of("START", "DONE", "START", "DONE"), statuses);
        assertEquals("需求分析师", events.get(0).role());
        assertEquals("分析结论AAA", events.get(1).output());
        assertEquals("架构师", events.get(2).role());

        // 上下文累积：第 2 次模型调用的提示词里应包含第 1 个角色的产出
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).stream(captor.capture(), any(), any());
        String secondPrompt = captor.getAllValues().get(1).get(0).getTextContent();
        assertTrue(secondPrompt.contains("分析结论AAA"), "second role prompt should carry first role output");
        assertTrue(secondPrompt.contains("写一个加法接口"), "should carry the original requirement");
    }

    @Test
    void failingRoleAbortsPipelineFastFail() {
        AdminCollaborationProperties properties = new AdminCollaborationProperties();
        properties.setRoles(List.of(planRole("需求分析师"), planRole("架构师"), planRole("开发")));
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any()))
            .thenReturn(Flux.just(response("分析结论AAA")))
            .thenReturn(Flux.error(new RuntimeException("model boom")))
            .thenReturn(Flux.just(response("不应到达")));

        CollaborativeCodingService service = newService(properties, model);
        List<RoleStageEvent> events = collect(service.stream("demo", "s1", "需求"));

        List<String> statuses = events.stream().map(RoleStageEvent::status).collect(Collectors.toList());
        // 第 2 个角色失败即中断，第 3 个角色不再产出任何事件
        assertEquals(List.of("START", "DONE", "START", "FAILED"), statuses);
        assertEquals("架构师", events.get(3).role());
        assertTrue(events.get(3).output().contains("失败"));
    }
}
