package com.richard.fyoung.customeradmin.workspace.vibecoding.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.GitDiffSummary;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.SandboxModeResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.SaveFileContentRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileContent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileNode;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.GitAssistantService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.VibeCodingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
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

    public VibeCodingController(VibeCodingService vibeCodingService, GitAssistantService gitAssistantService) {
        this.vibeCodingService = vibeCodingService;
        this.gitAssistantService = gitAssistantService;
    }

    @SaCheckPermission("workspace")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String agentCode, @Valid @RequestBody ChatRequest request) {
        return vibeCodingService.stream(agentCode, request.sessionId(), request.message())
            .map(chunk -> ServerSentEvent.<String>builder().event(chunk.kind().sseEventName()).data(chunk.text()).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
    }

    /** 当前 VibeCoding 沙箱模式（local/docker），全局配置，供前端在产物文件标题旁标注来源。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sandbox-mode")
    public Result<SandboxModeResponse> sandboxMode(@PathVariable String agentCode) {
        return Result.success(new SandboxModeResponse(vibeCodingService.sandboxMode()));
    }

    /** 安全中断该会话正在执行的流式对话，保留上下文以便后续续跑。 */
    @SaCheckPermission("workspace")
    @PostMapping("/sessions/{sessionId}/interrupt")
    public Result<Boolean> interrupt(@PathVariable String agentCode, @PathVariable String sessionId) {
        return Result.success(vibeCodingService.interrupt(agentCode, sessionId));
    }

    /** 变更文件清单：与本次 {@code stream} 调用用同一个 sessionId。 */
    @SaCheckPermission("workspace")
    @GetMapping("/artifacts")
    public Result<List<String>> artifacts(@PathVariable String agentCode, @RequestParam String sessionId) {
        return Result.success(vibeCodingService.listChangedArtifacts(agentCode, sessionId));
    }

    /**
     * 会话 workspace 目录树：返回 {@code sessions/{sessionId}/} 目录下所有文件/目录的树形结构。
     * 目录优先、同级按字母升序排列。
     */
    @SaCheckPermission("workspace")
    @GetMapping("/files")
    public Result<List<WorkspaceFileNode>> files(@PathVariable String agentCode, @RequestParam String sessionId) {
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
        return gitAssistantService.diffSummary(agentCode, sessionId).thenApply(Result::success);
    }

    /** Git 助手 · 生成 commit message（{@code style=conventional|simple}，默认 conventional）。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding生成commit message", target = "vibecoding_git_assistant")
    @PostMapping("/commit-message")
    public CompletableFuture<Result<CommitMessageResponse>> commitMessage(
            @PathVariable String agentCode, @Valid @RequestBody CommitMessageRequest request) {
        return gitAssistantService.commitMessage(agentCode, request.sessionId(), request.styleOrDefault())
            .thenApply(Result::success);
    }

    /** Git 助手 · 生成 PR description（Markdown：变更摘要/改动文件清单/影响范围/自检清单）。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "VibeCoding生成PR描述", target = "vibecoding_git_assistant")
    @PostMapping("/pr-description")
    public CompletableFuture<Result<PrDescriptionResponse>> prDescription(
            @PathVariable String agentCode, @Valid @RequestBody PrDescriptionRequest request) {
        return gitAssistantService.prDescription(agentCode, request.sessionId()).thenApply(Result::success);
    }
}
