package com.richard.fyoung.customeradmin.workspace.vibecoding.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.VibeCodingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * VibeCoding：对话（SSE，与 chat 同一套流式基础设施）+ 降级版产物清单
 * （对话结束后一次性对比 workspace 目录快照，见实施计划 3.4 节"一期用降级方案"）。
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
            .map(chunk -> ServerSentEvent.<String>builder().event("message").data(chunk).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
    }

    /** 变更文件清单：与本次 {@code stream} 调用用同一个 sessionId。 */
    @SaCheckPermission("workspace")
    @GetMapping("/artifacts")
    public Result<List<String>> artifacts(@PathVariable String agentCode, @RequestParam String sessionId) {
        return Result.success(vibeCodingService.listChangedArtifacts(agentCode, sessionId));
    }
}
