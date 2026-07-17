package com.richard.fyoung.customeradmin.workspace.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatAttachmentDTO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageVO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatSessionSummary;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatAttachmentService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatHistoryService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
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
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 智能体工作区对话（SSE）。权限点复用菜单聚合已经在用的 {@code workspace}——能看见工作区菜单
 * 节点的角色即可对话，不额外新增按智能体粒度的权限点（动态节点天然继承父节点可见性，
 * 见批次三 {@code MenuAggregationService} 的设计取舍）。
 *
 * <p>本模块是 Spring MVC（非 WebFlux），但 {@code reactor-core} 经 starter 传递可用，Spring MVC 6.x
 * 原生支持控制器方法返回 {@link Flux}&lt;{@link ServerSentEvent}&gt; 做流式响应（框架内置
 * {@code ReactiveTypeHandler}），无需手动桥接 {@code SseEmitter}，与
 * {@code CustomerServiceController#chatStream} 同一套写法。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workspace/{agentCode}/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final ChatAttachmentService chatAttachmentService;

    public ChatController(ChatService chatService, ChatHistoryService chatHistoryService,
                           ChatAttachmentService chatAttachmentService) {
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.chatAttachmentService = chatAttachmentService;
    }

    @SaCheckPermission("workspace")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String agentCode, @Valid @RequestBody ChatRequest request) {
        return chatService.chatStream(agentCode, request.sessionId(), request.message())
            // data 编码见 ChatStreamChunk#sseData：父 Agent 纯文本，子 Agent 片段 JSON 包装携带来源标识
            .map(chunk -> ServerSentEvent.<String>builder().event(chunk.kind().sseEventName()).data(chunk.sseData()).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
    }

    /** 历史会话列表（按最后一条消息时间倒序），供前端侧边栏展示。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sessions")
    public Result<List<ChatSessionSummary>> sessions(@PathVariable String agentCode) {
        return Result.success(chatHistoryService.listSessions(agentCode));
    }

    /** 重新打开某次历史会话的完整消息（含 vibecoding 的会话，两者共用同一套 session 状态）。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessageVO>> messages(@PathVariable String agentCode, @PathVariable String sessionId) {
        return Result.success(chatHistoryService.getMessages(agentCode, sessionId));
    }

    /** 安全中断该会话正在执行的流式对话，保留上下文以便后续续跑。 */
    @SaCheckPermission("workspace")
    @PostMapping("/sessions/{sessionId}/interrupt")
    public Result<Boolean> interrupt(@PathVariable String agentCode, @PathVariable String sessionId) {
        return Result.success(chatService.interrupt(agentCode, sessionId));
    }

    /**
     * 对话附件解析：多格式（图片视觉 OCR / pdf/office/html / md/txt/csv/json）解析成文本，落盘 + 落库，
     * 返回 {@link ChatAttachmentDTO}。前端把解析文本插进输入框，随普通消息一起发出供模型理解附件内容；
     * 解析失败时 {@code parseStatus=FAILED}、{@code content} 为空，由前端标红提示并跳过拼接。
     * {@code channel} 区分调用来源：ChatPanel 传 {@code admin_chat}、VibeCodingPanel 传 {@code vibecoding}。
     */
    @SaCheckPermission("workspace")
    @PostMapping("/attachment")
    public Result<ChatAttachmentDTO> parseAttachment(@PathVariable String agentCode,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "channel", defaultValue = "admin_chat") String channel,
                                                     @RequestParam(value = "sessionId", required = false) String sessionId) {
        return Result.success(chatAttachmentService.parseAttachment(file, channel, sessionId, agentCode));
    }
}
