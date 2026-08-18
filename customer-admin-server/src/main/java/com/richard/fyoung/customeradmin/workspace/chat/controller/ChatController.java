package com.richard.fyoung.customeradmin.workspace.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
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
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanConfirmRequest;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private final WorkspaceSessionGuard sessionGuard;

    public ChatController(ChatService chatService, ChatHistoryService chatHistoryService,
                           ChatAttachmentService chatAttachmentService, AgentCallMetaFactory agentCallMetaFactory,
                           WorkspaceSessionGuard sessionGuard) {
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
        this.chatAttachmentService = chatAttachmentService;
        this.agentCallMetaFactory = agentCallMetaFactory;
        this.sessionGuard = sessionGuard;
    }

    @SaCheckPermission("workspace")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String agentCode, @Valid @RequestBody ChatRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        sessionGuard.claimOrRequire(agentCode, request.sessionId(), userId);
        String tenantId = TenantContext.get();
        // 采集元数据必须在请求线程同步段构建（用户名取自 Sa-Token 的 ThreadLocal）；渠道=admin_chat → CHAT
        AgentCallMeta callMeta = agentCallMetaFactory.build(agentCode, AgentCallSessionType.CHAT, request.message());
        Flux<ServerSentEvent<String>> result = chatService.chatStreamWithAttachments(agentCode, request.sessionId(), request.message(), request.mode(), callMeta, request.attachmentIds())
            // data 编码见 ChatStreamChunk#sseData：父 Agent 纯文本，子 Agent 片段 JSON 包装携带来源标识
            .map(chunk -> ServerSentEvent.<String>builder().event(chunk.kind().sseEventName()).data(chunk.sseData()).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
        return tenantId == null ? result
            : result.contextWrite(context -> context.put(TenantContextThreadLocalAccessor.KEY, tenantId));
    }

    /**
     * 执行模式计划确认/拒绝（对话链路的 Plan 确认闭环，镜像 {@code VibeCodingController#confirmPlan}）：
     * 对 {@code plan} SSE 事件里的挂起操作放行或取消，复用 {@code PlanConfirmationService.confirm}。
     * planId 不存在/已处理/超时/服务重启后失效均返回 {@code PLAN_CONFIRM_NOT_FOUND}。
     */
    @SaCheckPermission("workspace")
    @PostMapping("/plan/confirm")
    public Result<Void> confirmPlan(@PathVariable String agentCode, @Valid @RequestBody PlanConfirmRequest request) {
        sessionGuard.requireOwned(agentCode, request.sessionId(), StpUtil.getLoginIdAsLong());
        chatService.confirmPlan(agentCode, request.sessionId(), request.planId(), request.approved());
        return Result.success(null);
    }

    /** 历史会话列表（按最后更新时间倒序、分页），供前端侧边栏滚动加载。默认每页 20 条（见 {@code ChatHistoryService#DEFAULT_PAGE_SIZE}）。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionSummary>> sessions(@PathVariable String agentCode,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size) {
        return Result.success(chatHistoryService.listSessions(agentCode, StpUtil.getLoginIdAsLong(), page, size));
    }

    /** 重新打开某次历史会话的完整消息（含 vibecoding 的会话，两者共用同一套 session 状态）。 */
    @SaCheckPermission("workspace")
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessageVO>> messages(@PathVariable String agentCode, @PathVariable String sessionId) {
        sessionGuard.requireOwned(agentCode, sessionId, StpUtil.getLoginIdAsLong());
        return Result.success(chatHistoryService.getMessages(agentCode, sessionId));
    }

    /** 安全中断该会话正在执行的流式对话，保留上下文以便后续续跑。 */
    @SaCheckPermission("workspace")
    @PostMapping("/sessions/{sessionId}/interrupt")
    public Result<Boolean> interrupt(@PathVariable String agentCode, @PathVariable String sessionId) {
        sessionGuard.requireOwned(agentCode, sessionId, StpUtil.getLoginIdAsLong());
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
                                                     @RequestParam("sessionId") String sessionId) {
        sessionGuard.claimOrRequire(agentCode, sessionId, StpUtil.getLoginIdAsLong());
        return Result.success(chatAttachmentService.parseAttachment(file, channel, sessionId, agentCode));
    }

    /**
     * 附件详情：返回 {@link ChatAttachmentDTO}（{@code content}=解析文本，供文本类附件内联预览）。
     * 校验附件存在 + agent_code 归属，跨 agent 访问统一 fast-fail 成资源不存在。
     */
    @SaCheckPermission("workspace")
    @GetMapping("/attachment/{attachmentId}")
    public Result<ChatAttachmentDTO> attachmentDetail(@PathVariable String agentCode,
                                                      @PathVariable String attachmentId) {
        return Result.success(chatAttachmentService.getDetail(
            agentCode, attachmentId, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 附件原文件下载：返回原始字节。Content-Type 取库中 mimeType（空则 application/octet-stream）；
     * 统一 {@code Content-Disposition: attachment}（一律作附件下载，不内联），文件名按 RFC 5987
     * {@code filename*=UTF-8''<url编码>} 支持中文名；加 {@code X-Content-Type-Options: nosniff} 防
     * html/svg 类附件被浏览器嗅探成可执行内容 inline 执行。校验附件存在 + agent_code 归属。
     */
    @SaCheckPermission("workspace")
    @GetMapping("/attachment/{attachmentId}/file")
    public ResponseEntity<byte[]> attachmentFile(@PathVariable String agentCode,
                                                  @PathVariable String attachmentId) {
        ChatAttachmentService.LoadedFile file = chatAttachmentService.loadFile(
            agentCode, attachmentId, StpUtil.getLoginIdAsLong());
        String encodedName = URLEncoder.encode(file.fileName() == null ? "" : file.fileName(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, file.mimeType())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
            .header("X-Content-Type-Options", "nosniff")
            .body(file.bytes());
    }
}
