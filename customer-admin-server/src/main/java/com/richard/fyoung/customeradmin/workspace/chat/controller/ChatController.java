package com.richard.fyoung.customeradmin.workspace.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatAttachmentDTO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageVO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatSessionSummary;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatAttachmentService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatHistoryService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.callstats.service.AgentCallMetaFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanConfirmRequest;
import com.richard.fyoung.customerwork.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.calllog.AgentCallSessionType;
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
    private final AgentCallMetaFactory agentCallMetaFactory;

    public ChatController(ChatService chatService, ChatHistoryService chatHistoryService,
                           ChatAttachmentService chatAttachmentService, AgentCallMetaFactory agentCallMetaFactory) {
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.chatAttachmentService = chatAttachmentService;
        this.agentCallMetaFactory = agentCallMetaFactory;
    }

    @SaCheckPermission("workspace")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String agentCode, @Valid @RequestBody ChatRequest request) {
        // 采集元数据必须在请求线程同步段构建（用户名取自 Sa-Token 的 ThreadLocal）；渠道=admin_chat → CHAT
        AgentCallMeta callMeta = agentCallMetaFactory.build(agentCode, AgentCallSessionType.CHAT, request.message());
        return chatService.chatStream(agentCode, request.sessionId(), request.message(), request.mode(), callMeta)
            // data 编码见 ChatStreamChunk#sseData：父 Agent 纯文本，子 Agent 片段 JSON 包装携带来源标识
            .map(chunk -> ServerSentEvent.<String>builder().event(chunk.kind().sseEventName()).data(chunk.sseData()).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
    }

    /**
     * 执行模式计划确认/拒绝（对话链路的 Plan 确认闭环，镜像 {@code VibeCodingController#confirmPlan}）：
     * 对 {@code plan} SSE 事件里的挂起操作放行或取消，复用 {@code PlanConfirmationService.confirm}。
     * planId 不存在/已处理/超时/服务重启后失效均返回 {@code PLAN_CONFIRM_NOT_FOUND}。
     */
    @SaCheckPermission("workspace")
    @PostMapping("/plan/confirm")
    public Result<Void> confirmPlan(@PathVariable String agentCode, @Valid @RequestBody PlanConfirmRequest request) {
        chatService.confirmPlan(agentCode, request.sessionId(), request.planId(), request.approved());
        return Result.success(null);
    }

    /** 历史会话列表（按最后更新时间倒序、分页），供前端侧边栏滚动加载。默认每页 20 条（见 {@code ChatHistoryService#DEFAULT_PAGE_SIZE}）。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionSummary>> sessions(@PathVariable String agentCode,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size) {
        return Result.success(chatHistoryService.listSessions(agentCode, page, size));
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
