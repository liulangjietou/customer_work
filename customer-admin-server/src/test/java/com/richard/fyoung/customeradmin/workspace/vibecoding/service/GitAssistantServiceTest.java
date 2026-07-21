package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.GitDiffSummary;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewResult;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewTaskVO;
import com.richard.fyoung.customeradmin.workspace.vibecoding.entity.CodeReviewTask;
import com.richard.fyoung.customeradmin.workspace.vibecoding.mapper.CodeReviewTaskMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GitAssistantService} 单测：能力校验、无变更时的快速失败/免模型调用、模型一次性调用结果拼装。
 * {@link GitWorkspaceService} 用 mock 代替（不依赖真实 git 命令，git 本身的行为由
 * {@link GitWorkspaceServiceTest} 单独覆盖）。
 * @author owlzhangfq@gmail.com
 */
class GitAssistantServiceTest {

    private static final ObjectMapper TEST_OBJECT_MAPPER = new ObjectMapper();

    private AiAgentMapper agentMapper;
    private AdminAgentInstanceFactory agentInstanceFactory;
    private GitWorkspaceService gitWorkspaceService;
    private CodeReviewTaskMapper reviewTaskMapper;
    private SiteMessageService siteMessageService;
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
        reviewTaskMapper = mock(CodeReviewTaskMapper.class);
        siteMessageService = mock(SiteMessageService.class);
        model = mock(Model.class);
        // 审计服务用 mock（旁路能力，埋点行为由 AiCodingAuditServiceTest 单独覆盖）
        service = new GitAssistantService(agentMapper, agentInstanceFactory, gitWorkspaceService,
            mock(AiCodingAuditService.class), reviewTaskMapper, siteMessageService);

        when(agentInstanceFactory.resolveSessionWorkspace(anyString(), anyString())).thenReturn(sessionWorkspace);
        when(agentInstanceFactory.buildModelForAgent(anyString())).thenReturn(model);
    }

    /** mock 的 insert 不会回填自增 id，异步链路要用 taskId 回写，故 stub 成插入即赋 id。 */
    private void stubReviewTaskInsertAssignsId(long taskId) {
        when(reviewTaskMapper.insert(any(CodeReviewTask.class))).thenAnswer(invocation -> {
            invocation.<CodeReviewTask>getArgument(0).setId(taskId);
            return 1;
        });
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

    // ===== code review 异步化 (提交-轮询) =====

    /** 从异步回写 updateById 捕获任务并反序列化 result_json，避免各用例重复模板。 */
    private CodeReviewTask captureReviewTaskUpdate() {
        ArgumentCaptor<CodeReviewTask> captor = ArgumentCaptor.forClass(CodeReviewTask.class);
        verify(reviewTaskMapper, timeout(2000)).updateById(captor.capture());
        return captor.getValue();
    }

    private ReviewResult parseResultJson(String json) throws Exception {
        return TEST_OBJECT_MAPPER.readValue(json, ReviewResult.class);
    }

    @Test
    void submitReview_shouldFastFailNoFileChanges_synchronously_whenDiffEmpty() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace)).thenReturn("");

        // 无变更同步快速失败：既不建任务行、也不发站内信
        BizException ex = assertThrows(BizException.class, () -> service.submitReview("coder", "s1", 7L));
        assertEquals(ResultCode.NO_FILE_CHANGES, ex.getResultCode());
        verify(reviewTaskMapper, never()).insert(any(CodeReviewTask.class));
    }

    @Test
    void submitReview_shouldReturnTaskIdImmediately_andPersistRunningRow() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+class Foo {}");
        stubReviewTaskInsertAssignsId(42L);
        mockModelReply("{\"issues\":[],\"summary\":\"ok\"}");

        Long taskId = service.submitReview("coder", "s1", 7L);

        assertEquals(42L, taskId, "同步段应立即返回插入后回填的 taskId");
    }

    @Test
    void submitReview_shouldPersistSuccessAndSendMessage_whenModelReturnsJson() throws Exception {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+String sql = \"select * where id=\"+id;");
        stubReviewTaskInsertAssignsId(99L);
        // 模型在 JSON 外还夹了 Markdown 代码块标记，解析器应能剥离
        mockModelReply("```json\n{\"issues\":[{\"severity\":\"CRITICAL\",\"file\":\"Foo.java\",\"line\":1,"
            + "\"category\":\"SECURITY\",\"message\":\"SQL 注入\",\"suggestion\":\"用参数化查询\"}],"
            + "\"summary\":\"发现 1 个严重问题\"}\n```");

        service.submitReview("coder", "s1", 7L);

        CodeReviewTask updated = captureReviewTaskUpdate();
        assertEquals(CodeReviewTask.STATUS_SUCCESS, updated.getStatus());
        assertNotNull(updated.getFinishTime());
        ReviewResult result = parseResultJson(updated.getResultJson());
        assertEquals(1, result.issues().size());
        assertEquals("CRITICAL", result.issues().get(0).severity());
        assertEquals("SECURITY", result.issues().get(0).category());
        // 成功站内信：接收人=提交人、bizType=CODE_REVIEW、bizId=taskId、link 携带 reviewTask=taskId
        verify(siteMessageService, timeout(2000)).send(eq(7L), eq("AI 代码审查完成"), anyString(),
            eq("CODE_REVIEW"), eq("99"), contains("reviewTask=99"));
    }

    @Test
    void submitReview_shouldPersistDegradedSummary_whenModelReturnsNonJson() throws Exception {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+class Foo {}");
        stubReviewTaskInsertAssignsId(99L);
        mockModelReply("这段代码看起来没什么问题，整体不错。");

        service.submitReview("coder", "s1", 7L);

        CodeReviewTask updated = captureReviewTaskUpdate();
        // 解析失败仍算成功（降级），不当作硬失败
        assertEquals(CodeReviewTask.STATUS_SUCCESS, updated.getStatus());
        ReviewResult result = parseResultJson(updated.getResultJson());
        assertTrue(result.issues().isEmpty(), "解析失败降级为空 issues");
        assertTrue(result.summary().contains("整体不错"), "模型原文进 summary");
    }

    @Test
    void submitReview_shouldNormalizeSeverityAndCategory_lowercaseMixedcaseAndUnknown() throws Exception {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+class Foo {}");
        stubReviewTaskInsertAssignsId(99L);
        // 三种非规范值：全小写 / 混合大小写 / 未知值（blocker、smell 不在合法集合内）
        mockModelReply("{\"issues\":["
            + "{\"severity\":\"critical\",\"file\":\"A.java\",\"line\":1,\"category\":\"security\",\"message\":\"m1\",\"suggestion\":\"s1\"},"
            + "{\"severity\":\"Warning\",\"file\":\"B.java\",\"line\":2,\"category\":\"Bug\",\"message\":\"m2\",\"suggestion\":\"s2\"},"
            + "{\"severity\":\"blocker\",\"file\":\"C.java\",\"line\":3,\"category\":\"smell\",\"message\":\"m3\",\"suggestion\":\"s3\"}],"
            + "\"summary\":\"3 项\"}");

        service.submitReview("coder", "s1", 7L);

        ReviewResult result = parseResultJson(captureReviewTaskUpdate().getResultJson());
        assertEquals(3, result.issues().size(), "非规范值不能导致 issue 丢失");
        assertEquals("CRITICAL", result.issues().get(0).severity(), "全小写应归一为大写");
        assertEquals("SECURITY", result.issues().get(0).category());
        assertEquals("WARNING", result.issues().get(1).severity(), "混合大小写应归一为大写");
        assertEquals("BUG", result.issues().get(1).category());
        assertEquals("SUGGESTION", result.issues().get(2).severity(), "未知 severity 兜底 SUGGESTION");
        assertEquals("STYLE", result.issues().get(2).category(), "未知 category 兜底 STYLE");
    }

    @Test
    void submitReview_shouldPersistFailedAndSendFailureMessage_whenModelReturnsNothing() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(gitWorkspaceService.diffAgainstBaseline(sessionWorkspace))
            .thenReturn("diff --git a/Foo.java b/Foo.java\n+class Foo {}");
        stubReviewTaskInsertAssignsId(99L);
        // 模型空响应：callModelOnce 内部抛异常 → 异步链路落 FAILED 并发失败站内信（不抛给调用方）
        when(model.stream(any(), any(), any())).thenReturn(Flux.empty());

        service.submitReview("coder", "s1", 7L);

        CodeReviewTask updated = captureReviewTaskUpdate();
        assertEquals(CodeReviewTask.STATUS_FAILED, updated.getStatus());
        assertNotNull(updated.getErrorMsg(), "失败任务应落错误原因");
        assertNotNull(updated.getFinishTime());
        verify(siteMessageService, timeout(2000)).send(eq(7L), eq("AI 代码审查失败"), anyString(),
            eq("CODE_REVIEW"), eq("99"), contains("reviewTask=99"));
    }

    // ===== 审查任务轮询 (归属校验) =====

    @Test
    void getReviewTask_shouldThrowNotFound_whenTaskMissing() {
        when(reviewTaskMapper.selectById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.getReviewTask(1L, 7L));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
    }

    @Test
    void getReviewTask_shouldThrowForbidden_whenNotOwner() {
        CodeReviewTask task = new CodeReviewTask();
        task.setId(1L);
        task.setUserId(999L);
        task.setStatus(CodeReviewTask.STATUS_RUNNING);
        when(reviewTaskMapper.selectById(1L)).thenReturn(task);

        BizException ex = assertThrows(BizException.class, () -> service.getReviewTask(1L, 7L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
    }

    @Test
    void getReviewTask_shouldReturnResult_whenSuccessAndOwned() {
        CodeReviewTask task = new CodeReviewTask();
        task.setId(1L);
        task.setUserId(7L);
        task.setStatus(CodeReviewTask.STATUS_SUCCESS);
        task.setResultJson("{\"issues\":[],\"summary\":\"looks good\"}");
        when(reviewTaskMapper.selectById(1L)).thenReturn(task);

        ReviewTaskVO vo = service.getReviewTask(1L, 7L);

        assertEquals(CodeReviewTask.STATUS_SUCCESS, vo.status());
        assertNotNull(vo.result());
        assertEquals("looks good", vo.result().summary());
    }

    @Test
    void getReviewTask_shouldReturnNullResult_whenStillRunning() {
        CodeReviewTask task = new CodeReviewTask();
        task.setId(1L);
        task.setUserId(7L);
        task.setStatus(CodeReviewTask.STATUS_RUNNING);
        when(reviewTaskMapper.selectById(1L)).thenReturn(task);

        ReviewTaskVO vo = service.getReviewTask(1L, 7L);

        assertEquals(CodeReviewTask.STATUS_RUNNING, vo.status());
        assertTrue(vo.result() == null, "RUNNING 阶段无结果");
    }
}
