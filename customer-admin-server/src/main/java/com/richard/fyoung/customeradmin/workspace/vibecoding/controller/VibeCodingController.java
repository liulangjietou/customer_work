package com.richard.fyoung.customeradmin.workspace.vibecoding.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.GitDiffSummary;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanConfirmRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewTaskVO;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RollbackRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RollbackResult;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.SandboxModeResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.SaveFileContentRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileContent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileNode;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommandExecuteRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.DiagnoseRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ManagedSandboxView;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RefactorTask;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.SandboxConfigView;
import com.richard.fyoung.customeradmin.workspace.runtime.SandboxCommandEvent;
import com.richard.fyoung.customeradmin.workspace.runtime.SandboxCommandService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.AiCodingTaskService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.CollaborativeCodingService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.GitAssistantService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.VibeCodingService;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * VibeCoding：对话（SSE，与 chat 同一套流式基础设施）+ 产物清单（快照 diff）+
 * 会话 workspace 目录树＋文件内容预览。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workspace/{agentCode}/vibecoding")
public class VibeCodingController {

    private final VibeCodingService vibeCodingService;
    private final GitAssistantService gitAssistantService;
    private final CollaborativeCodingService collaborativeCodingService;
    private final WorkspaceSessionGuard sessionGuard;
    private final SandboxCommandService sandboxCommandService;
    private final AiCodingTaskService aiCodingTaskService;
    private final ObjectMapper objectMapper;

    public VibeCodingController(VibeCodingService vibeCodingService, GitAssistantService gitAssistantService,
                               CollaborativeCodingService collaborativeCodingService,
                               WorkspaceSessionGuard sessionGuard, SandboxCommandService sandboxCommandService,
                               AiCodingTaskService aiCodingTaskService, ObjectMapper objectMapper) {
        this.vibeCodingService = vibeCodingService;
        this.gitAssistantService = gitAssistantService;
        this.collaborativeCodingService = collaborativeCodingService;
        this.sessionGuard = sessionGuard;
        this.sandboxCommandService = sandboxCommandService;
        this.aiCodingTaskService = aiCodingTaskService;
        this.objectMapper = objectMapper;
    }

    /**
     * VibeCoding 流式对话。{@code request.collaboration=true}（前端"协作模式"开关，默认关，P3-1）时走多角色
     * 顺序流水（需求分析→方案设计→编码实现→自测审查），额外产出 {@code role_stage} SSE 事件；否则走单 Agent
     * 常规流。两条路径统一映射成 SSE 事件后追加 {@code done} 收尾。
     */
    @SaCheckPermission("workspace")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String agentCode, @Valid @RequestBody ChatRequest request) {
        sessionGuard.claimOrRequire(agentCode, request.sessionId(), StpUtil.getLoginIdAsLong());
        String tenantId = TenantContext.get();
        Flux<com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk> source = request.collaborationEnabled()
            ? collaborativeCodingService.stream(agentCode, request.sessionId(), request.message(), request.mode(), request.attachmentIds())
            : vibeCodingService.stream(agentCode, request.sessionId(), request.message(), request.mode(), request.attachmentIds());
        Flux<ServerSentEvent<String>> result = source
            // data 编码见 ChatStreamChunk#sseData：父 Agent 纯文本，子 Agent 片段 JSON 包装携带来源标识
            .map(chunk -> ServerSentEvent.<String>builder().event(chunk.kind().sseEventName()).data(chunk.sseData()).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
        return tenantId == null ? result
            : result.contextWrite(context -> context.put(TenantContextThreadLocalAccessor.KEY, tenantId));
    }

    /** 当前 VibeCoding 沙箱模式（local/docker），全局配置，供前端在产物文件标题旁标注来源。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sandbox-mode")
    public Result<SandboxModeResponse> sandboxMode(@PathVariable String agentCode) {
        return Result.success(new SandboxModeResponse(vibeCodingService.sandboxMode()));
    }

    /** 交互式命令执行：实时输出、结构化测试报告与唯一终态均通过 SSE 返回。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding执行命令", target = "vibecoding_command")
    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> execute(@PathVariable String agentCode,
                                                  @Valid @RequestBody CommandExecuteRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        sessionGuard.claimOrRequire(agentCode, request.sessionId(), userId);
        String tenantId = TenantContext.get();
        Flux<ServerSentEvent<String>> result;
        try {
            result = sandboxCommandService.execute(agentCode, request.sessionId(), userId, request.command())
                .map(this::toCommandSse)
                .onErrorResume(error -> Flux.just(commandError(error)))
                .concatWithValues(doneEvent());
        } catch (RuntimeException error) {
            result = Flux.just(commandError(error), doneEvent());
        }
        return tenantId == null ? result
            : result.contextWrite(context -> context.put(TenantContextThreadLocalAccessor.KEY, tenantId));
    }

    /** 根据粘贴的异常堆栈/日志定位、修复并验证，事件协议与常规 VibeCoding 完全一致。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding日志诊断", target = "vibecoding_diagnose")
    @PostMapping(value = "/diagnose", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> diagnose(@PathVariable String agentCode,
                                                   @Valid @RequestBody DiagnoseRequest request) {
        sessionGuard.claimOrRequire(agentCode, request.sessionId(), StpUtil.getLoginIdAsLong());
        return codingTaskStream(aiCodingTaskService.diagnose(agentCode, request.sessionId(), request.log()));
    }

    /** 自动化重构：接口先发任务级 plan，确认后才进入文件修改与测试链路。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding自动化重构", target = "vibecoding_refactor")
    @PostMapping(value = "/refactor", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> refactor(@PathVariable String agentCode,
                                                   @Valid @RequestBody RefactorTask task) {
        sessionGuard.claimOrRequire(agentCode, task.sessionId(), StpUtil.getLoginIdAsLong());
        return codingTaskStream(aiCodingTaskService.refactor(agentCode, task));
    }

    /** 当前生效的 {@code admin.sandbox.*} 安全只读视图。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sandbox/config")
    public Result<SandboxConfigView> sandboxConfig(@PathVariable String agentCode) {
        return Result.success(sandboxCommandService.config());
    }

    /** 当前用户在该智能体下创建的会话沙箱运行态。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sandbox/sessions")
    public Result<List<ManagedSandboxView>> sandboxes(@PathVariable String agentCode) {
        return Result.success(sandboxCommandService.list(agentCode, StpUtil.getLoginIdAsLong()));
    }

    /** 停止并删除一个归属于当前用户的会话沙箱；清理接口幂等。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding清理沙箱", target = "vibecoding_sandbox")
    @DeleteMapping("/sandbox/sessions/{sessionId}")
    public Result<Boolean> cleanupSandbox(@PathVariable String agentCode, @PathVariable String sessionId) {
        requireOwned(agentCode, sessionId);
        return Result.success(sandboxCommandService.cleanup(agentCode, sessionId, StpUtil.getLoginIdAsLong()));
    }

    /** 安全中断该会话正在执行的流式对话，保留上下文以便后续续跑。 */
    @SaCheckPermission("workspace")
    @PostMapping("/sessions/{sessionId}/interrupt")
    public Result<Boolean> interrupt(@PathVariable String agentCode, @PathVariable String sessionId) {
        requireOwned(agentCode, sessionId);
        return Result.success(vibeCodingService.interrupt(agentCode, sessionId));
    }

    /** 变更文件清单：与本次 {@code stream} 调用用同一个 sessionId。 */
    @SaCheckPermission("workspace")
    @GetMapping("/artifacts")
    public Result<List<String>> artifacts(@PathVariable String agentCode, @RequestParam String sessionId) {
        requireOwned(agentCode, sessionId);
        return Result.success(vibeCodingService.listChangedArtifacts(agentCode, sessionId));
    }

    /**
     * 会话 workspace 目录树：返回 {@code sessions/{sessionId}/} 目录下所有文件/目录的树形结构。
     * 目录优先、同级按字母升序排列。
     */
    @SaCheckPermission("workspace")
    @GetMapping("/files")
    public Result<List<WorkspaceFileNode>> files(@PathVariable String agentCode, @RequestParam String sessionId) {
        requireOwned(agentCode, sessionId);
        return Result.success(vibeCodingService.listWorkspaceFiles(agentCode, sessionId));
    }

    /**
     * 读取文件内容：返回指定路径文件的文本内容与语言标识。
     * 内置路径穿越防御：{@code path} 必须属于 {@code sessions/{sessionId}/} 目录内。
     *
     * @param path 相对于会话 workspace 的文件路径（如 {@code src/main/java/Foo.java}）
     */
    @SaCheckPermission("workspace")
    @GetMapping("/file-content")
    public Result<WorkspaceFileContent> fileContent(
            @PathVariable String agentCode,
            @RequestParam String sessionId,
            @RequestParam String path) {
        requireOwned(agentCode, sessionId);
        return Result.success(vibeCodingService.readFileContent(agentCode, sessionId, path));
    }

    /**
     * 保存文件内容：将编辑后的内容写入指定文件（存在则覆盖，不存在则创建）。
     * 内置路径穿越防御：{@code relativePath} 必须属于 {@code sessions/{sessionId}/} 目录内。
     */
    @SaCheckPermission("workspace")
    @PutMapping("/file-content")
    public Result<Void> saveFileContent(
            @PathVariable String agentCode,
            @Valid @RequestBody SaveFileContentRequest request) {
        requireOwned(agentCode, request.sessionId());
        vibeCodingService.saveFileContent(agentCode, request.sessionId(), request.relativePath(), request.content());
        return Result.success(null);
    }

    /**
     * Git 助手 · diff 摘要：会话 workspace 相对基线的变更文件清单 + AI 生成的 1~3 句话摘要。
     * 与 {@code artifacts} 的区别：{@code artifacts} 只给文件路径清单，这里额外给自然语言摘要，
     * 且底层走真实 {@code git diff}（有 unified diff 文本），不是文件指纹对比。
     */
    @SaCheckPermission("workspace")
    @GetMapping("/git-diff")
    public CompletableFuture<Result<GitDiffSummary>> gitDiff(@PathVariable String agentCode, @RequestParam String sessionId) {
        requireOwned(agentCode, sessionId);
        return gitAssistantService.diffSummary(agentCode, sessionId).thenApply(Result::success);
    }

    /** Git 助手 · 生成 commit message（{@code style=conventional|simple}，默认 conventional）。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding生成commit message", target = "vibecoding_git_assistant")
    @PostMapping("/commit-message")
    public CompletableFuture<Result<CommitMessageResponse>> commitMessage(
            @PathVariable String agentCode, @Valid @RequestBody CommitMessageRequest request) {
        requireOwned(agentCode, request.sessionId());
        return gitAssistantService.commitMessage(agentCode, request.sessionId(), request.styleOrDefault())
            .thenApply(Result::success);
    }

    /** Git 助手 · 生成 PR description（Markdown：变更摘要/改动文件清单/影响范围/自检清单）。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding生成PR描述", target = "vibecoding_git_assistant")
    @PostMapping("/pr-description")
    public CompletableFuture<Result<PrDescriptionResponse>> prDescription(
            @PathVariable String agentCode, @Valid @RequestBody PrDescriptionRequest request) {
        requireOwned(agentCode, request.sessionId());
        return gitAssistantService.prDescription(agentCode, request.sessionId()).thenApply(Result::success);
    }

    /**
     * Git 助手 · 提交 AI 代码审查（需求 P0-2，提交-轮询模型）：模型分钟级调用不再阻塞前端，
     * 立即返回 taskId，前端凭此轮询 {@link #reviewTask}。无变更等校验失败在提交同步段即报错。
     * 提交人取自当前登录态并透传到异步链路（异步线程拿不到 Sa-Token 上下文）。
     */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding代码审查", target = "vibecoding_git_assistant")
    @PostMapping("/review")
    public Result<Long> review(@PathVariable String agentCode, @Valid @RequestBody ReviewRequest request) {
        requireOwned(agentCode, request.sessionId());
        return Result.success(gitAssistantService.submitReview(agentCode, request.sessionId(), StpUtil.getLoginIdAsLong()));
    }

    /**
     * Git 助手 · 轮询 AI 代码审查任务：RUNNING 表示进行中，SUCCESS 携带结构化结果，FAILED 携带错误。
     * 校验任务归属，非本人快速失败。
     */
    @SaCheckPermission("workspace")
    @GetMapping("/review-task/{taskId}")
    public Result<ReviewTaskVO> reviewTask(@PathVariable String agentCode, @PathVariable Long taskId) {
        return Result.success(gitAssistantService.getReviewTask(taskId, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 会话一键回滚（需求 P0-1）：撤销本次会话对 workspace 的全部文件改动，恢复到对话前的 baseline 状态。
     * 破坏性操作（{@code git checkout} + {@code clean}）；仅支持 local 沙箱模式，baseline 缺失时 fast fail。
     */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding会话回滚", target = "vibecoding_rollback")
    @PostMapping("/rollback")
    public Result<RollbackResult> rollback(@PathVariable String agentCode, @Valid @RequestBody RollbackRequest request) {
        requireOwned(agentCode, request.sessionId());
        return Result.success(vibeCodingService.rollback(agentCode, request.sessionId()));
    }

    /**
     * Plan Mode 计划确认/拒绝（需求 P1-1）：对 {@code plan} SSE 事件里的高风险操作放行或取消。
     * 批准 → 流恢复执行该操作；拒绝 → 该操作被取消（改写为无害占位）、Agent 调整方案，流正常继续。
     * planId 不存在/已处理/超时/服务重启后失效均返回 {@code PLAN_CONFIRM_NOT_FOUND}。
     */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding计划确认", target = "vibecoding_plan_confirm")
    @PostMapping("/plan/confirm")
    public Result<Void> confirmPlan(@PathVariable String agentCode, @Valid @RequestBody PlanConfirmRequest request) {
        requireOwned(agentCode, request.sessionId());
        vibeCodingService.confirmPlan(agentCode, request.sessionId(), request.planId(), request.approved(), request.note());
        return Result.success(null);
    }

    private void requireOwned(String agentCode, String sessionId) {
        sessionGuard.requireOwned(agentCode, sessionId, StpUtil.getLoginIdAsLong());
    }

    private Flux<ServerSentEvent<String>> codingTaskStream(
            Flux<com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk> source) {
        String tenantId = TenantContext.get();
        Flux<ServerSentEvent<String>> result = source
            .map(chunk -> ServerSentEvent.<String>builder()
                .event(chunk.kind().sseEventName()).data(chunk.sseData()).build())
            .concatWithValues(doneEvent());
        return tenantId == null ? result
            : result.contextWrite(context -> context.put(TenantContextThreadLocalAccessor.KEY, tenantId));
    }

    private ServerSentEvent<String> toCommandSse(SandboxCommandEvent event) {
        return ServerSentEvent.<String>builder().event(event.event()).data(toJson(event.payload())).build();
    }

    private ServerSentEvent<String> commandError(Throwable error) {
        ResultCode code = error instanceof BizException bizException
            ? bizException.getResultCode() : ResultCode.SANDBOX_RUNTIME_FAILED;
        return ServerSentEvent.<String>builder().event("command_error")
            .data(toJson(Map.of("code", code.getCode(), "message", error.getMessage() == null
                ? code.getMessage() : error.getMessage()))).build();
    }

    private ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.<String>builder().event("done").data("[DONE]").build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "SSE事件序列化失败");
        }
    }
}
