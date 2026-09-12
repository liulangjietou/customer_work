package com.richard.fyoung.customeradmin.workspace.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentPreviewVO;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatKnowledgeSourcesVO;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatKnowledgeSourcesService;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作区内的消息来源出口；会话归属与文档 ACL 同时生效，不扩大到全库浏览。 */
@RestController
@SaCheckPermission("workspace")
@RequestMapping("/api/workspace/{agentCode}/chat/sessions/{sessionId}/messages/{messageId}/sources")
public class ChatKnowledgeSourcesController {
    private final ChatKnowledgeSourcesService service;
    private final WorkspaceSessionGuard sessionGuard;

    public ChatKnowledgeSourcesController(ChatKnowledgeSourcesService service, WorkspaceSessionGuard sessionGuard) {
        this.service = service;
        this.sessionGuard = sessionGuard;
    }

    /** 来源缺失明确返回未记录，权限和服务错误由统一错误协议呈现。 */
    @GetMapping
    public Result<ChatKnowledgeSourcesVO> sources(@PathVariable String agentCode, @PathVariable String sessionId,
        @PathVariable String messageId, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        sessionGuard.requireOwned(agentCode, sessionId, StpUtil.getLoginIdAsLong());
        return Result.success(service.sources(agentCode, sessionId, messageId, identity()));
    }

    /** 原文只按已保存来源读取，不接受外部 URL 或浏览器构造的版本标识。 */
    @GetMapping("/{sourceId}/preview")
    public Result<KnowledgeDocumentPreviewVO> preview(@PathVariable String agentCode, @PathVariable String sessionId,
        @PathVariable String messageId, @PathVariable int sourceId, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        sessionGuard.requireOwned(agentCode, sessionId, StpUtil.getLoginIdAsLong());
        return Result.success(service.preview(agentCode, sessionId, messageId, sourceId, identity()));
    }

    private AgentInvocationIdentity identity() {
        return new AgentInvocationIdentity(TenantContext.require(), QuotaSubjectType.ADMIN_USER,
            StpUtil.getLoginIdAsString(), true).withChannel(AgentInvocationIdentity.CHANNEL_ADMIN);
    }
}
