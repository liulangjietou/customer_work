package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.GitDiffSummary;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionResponse;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GitAssistantService} 单测：能力校验、无变更时的快速失败/免模型调用、模型一次性调用结果拼装。
 * {@link GitWorkspaceService} 用 mock 代替（不依赖真实 git 命令，git 本身的行为由
 * {@link GitWorkspaceServiceTest} 单独覆盖）。
 * @author owlzhangfq@gmail.com
 */
class GitAssistantServiceTest {

    private AiAgentMapper agentMapper;
    private AdminAgentInstanceFactory agentInstanceFactory;
    private GitWorkspaceService gitWorkspaceService;
    private Model model;
    private GitAssistantService service;

    @TempDir
    Path sessionWorkspace;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        agentMapper = mock(AiAgentMapper.class);
        agentInstanceFactory = mock(AdminAgentInstanceFactory.class);
        gitWorkspaceService = mock(GitWorkspaceService.class);
        model = mock(Model.class);
        service = new GitAssistantService(agentMapper, agentInstanceFactory, gitWorkspaceService);

        when(agentInstanceFactory.resolveSessionWorkspace(anyString(), anyString())).thenReturn(sessionWorkspace);
        when(agentInstanceFactory.buildModelForAgent(anyString())).thenReturn(model);
    }

    private AiAgent vibeCodingAgent() {
        AiAgent agent = new AiAgent();
        agent.setAgentCode("coder");
        agent.setCapabilities("chat,vibecoding");
        return agent;
    }

    private void mockModelReply(String text) {
        ChatResponse response = ChatResponse.builder()
            .content(List.of(TextBlock.builder().text(text).build()))
            .build();
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(response));
    }

    private BizException unwrap(CompletionException e) {
        return (BizException) e.getCause();
    }

    // ===== 能力校验 =====

    @Test
    void diffSummary_shouldReject_whenAgentNotVibeCodingCapable() {
        AiAgent chatOnly = new AiAgent();
        chatOnly.setCapabilities("chat");
        when(agentMapper.selectOne(any())).thenReturn(chatOnly);

        assertThrows(BizException.class, () -> service.diffSummary("coder", "s1"));
    }

    // ===== diff 摘要 =====

    @Test
    void diffSummary_shouldSkipModelCall_whenNoChangedFiles() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace)).thenReturn("");
        when(gitWorkspaceService.changedFilesAgainstBaseline(sessionWorkspace)).thenReturn(List.of());

        GitDiffSummary summary = service.diffSummary("coder", "s1").join();

        assertEquals("本轮对话暂无文件变更", summary.summary());
        assertTrue(summary.changedFiles().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test
    void diffSummary_shouldCallModel_whenFilesChanged() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+class Foo {}");
        when(gitWorkspaceService.changedFilesAgainstBaseline(sessionWorkspace)).thenReturn(List.of("Foo.java"));
        mockModelReply("新增了 Foo 类。");

        GitDiffSummary summary = service.diffSummary("coder", "s1").join();

        assertEquals("新增了 Foo 类。", summary.summary());
        assertEquals(List.of("Foo.java"), summary.changedFiles());
    }

    // ===== commit message =====

    @Test
    void commitMessage_shouldThrowNoFileChanges_whenDiffEmpty() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace)).thenReturn("");

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.commitMessage("coder", "s1", "conventional").join());
        assertEquals(ResultCode.NO_FILE_CHANGES, unwrap(ex).getResultCode());
    }

    @Test
    void commitMessage_shouldReturnGeneratedMessage_whenDiffPresent() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+class Foo {}");
        mockModelReply("feat(coder): add Foo class");

        CommitMessageResponse response = service.commitMessage("coder", "s1", "conventional").join();

        assertEquals("feat(coder): add Foo class", response.message());
    }

    // ===== PR description =====

    @Test
    void prDescription_shouldThrowNoFileChanges_whenDiffEmpty() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace)).thenReturn("");

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.prDescription("coder", "s1").join());
        assertEquals(ResultCode.NO_FILE_CHANGES, unwrap(ex).getResultCode());
    }

    @Test
    void prDescription_shouldReturnMarkdown_whenDiffPresent() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+class Foo {}");
        when(gitWorkspaceService.changedFilesAgainstBaseline(sessionWorkspace)).thenReturn(List.of("Foo.java"));
        mockModelReply("## 变更摘要\n新增 Foo 类\n## 改动文件清单\n- Foo.java\n## 影响范围\n无\n## 自检清单\n- [x] 编译通过");

        PrDescriptionResponse response = service.prDescription("coder", "s1").join();

        assertTrue(response.description().contains("## 变更摘要"));
        assertTrue(response.description().contains("Foo.java"));
    }
}
