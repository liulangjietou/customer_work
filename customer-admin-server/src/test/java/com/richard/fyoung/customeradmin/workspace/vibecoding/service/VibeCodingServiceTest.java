package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link VibeCodingService} 单测：vibecoding 能力校验 + 降级版产物清单（workspace 目录快照 diff）。
 * @author owlzhangfq@gmail.com
 */
class VibeCodingServiceTest {

    private ChatService chatService;
    private AdminAgentInstanceFactory agentInstanceFactory;
    private AiAgentMapper agentMapper;
    private VibeCodingService service;

    @TempDir
    Path workspace;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        agentInstanceFactory = mock(AdminAgentInstanceFactory.class);
        agentMapper = mock(AiAgentMapper.class);
        service = new VibeCodingService(chatService, agentInstanceFactory, agentMapper);
        when(agentInstanceFactory.resolveWorkspace("coder")).thenReturn(workspace);
    }

    private AiAgent vibeCodingAgent() {
        AiAgent agent = new AiAgent();
        agent.setAgentCode("coder");
        agent.setCapabilities("chat,vibecoding");
        return agent;
    }

    @Test
    void stream_shouldRejectUnknownAgent() {
        when(agentMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.stream("coder", "s1", "写个脚本"));
    }

    @Test
    void stream_shouldRejectAgentWithoutVibeCodingCapability() {
        AiAgent chatOnly = new AiAgent();
        chatOnly.setAgentCode("coder");
        chatOnly.setCapabilities("chat");
        when(agentMapper.selectOne(any())).thenReturn(chatOnly);

        assertThrows(BizException.class, () -> service.stream("coder", "s1", "写个脚本"));
    }

    @Test
    void stream_shouldDelegateToChatService_whenCapable() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(chatService.chatStream("coder", "s1", "写个脚本")).thenReturn(Flux.just("好的"));

        List<String> emitted = service.stream("coder", "s1", "写个脚本").collectList().block();

        assertEquals(List.of("好的"), emitted);
    }

    @Test
    void listChangedArtifacts_shouldDetectNewAndModifiedFiles_butNotUntouchedFiles() throws IOException {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(chatService.chatStream(anyString(), anyString(), anyString())).thenReturn(Flux.empty());

        Files.writeString(workspace.resolve("untouched.txt"), "same content throughout");
        Files.writeString(workspace.resolve("to-modify.txt"), "original");

        service.stream("coder", "s1", "写个脚本").blockLast();

        Files.writeString(workspace.resolve("to-modify.txt"), "changed content, different size");
        Files.writeString(workspace.resolve("new-file.txt"), "brand new");

        List<String> changed = service.listChangedArtifacts("coder", "s1");

        assertTrue(changed.contains("new-file.txt"));
        assertTrue(changed.contains("to-modify.txt"));
        assertFalse(changed.contains("untouched.txt"));
    }

    @Test
    void listChangedArtifacts_shouldReturnEmpty_whenNoPriorStreamCallForSession() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());

        List<String> changed = service.listChangedArtifacts("coder", "never-started-session");

        // 没有 before 快照时，当前目录任意已存在文件都会被视为"新增"，此处目录为空所以清单也为空
        assertEquals(List.of(), changed);
    }
}
