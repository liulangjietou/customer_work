package com.richard.fyoung.customeradmin.workspace.vibecoding.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.SaveFileContentRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileContent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileNode;
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

/**
 * VibeCoding：对话（SSE，与 chat 同一套流式基础设施）+ 产物清单（快照 diff）+
 * 会话 workspace 目录树＋文件内容预览。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workspace/{agentCode}/vibecoding")
public class VibeCodingController {

    private final VibeCodingService vibeCodingService;

    public VibeCodingController(VibeCodingService vibeCodingService) {
        this.vibeCodingService = vibeCodingService;
    }

    @SaCheckPermission("workspace")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String agentCode, @Valid @RequestBody ChatRequest request) {
        return vibeCodingService.stream(agentCode, request.sessionId(), request.message())
            .map(chunk -> ServerSentEvent.<String>builder().event(chunk.kind().sseEventName()).data(chunk.text()).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
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
}
