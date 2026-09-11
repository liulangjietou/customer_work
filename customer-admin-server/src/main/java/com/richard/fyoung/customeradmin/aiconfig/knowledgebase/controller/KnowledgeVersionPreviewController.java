package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentPreviewVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeVersionDocumentVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeVersionPreviewService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 原文构成新的数据出口：独立权限与文档 ACL 同时生效，身份仅取自当前登录请求。 */
@RestController
@RequestMapping("/api/aiconfig/knowledge-base/{knowledgeBaseId}/versions/{versionId}/documents")
@SaCheckPermission({"knowledge-base:view", "knowledge-base:source-preview"})
public class KnowledgeVersionPreviewController {

    private final KnowledgeVersionPreviewService service;

    public KnowledgeVersionPreviewController(KnowledgeVersionPreviewService service) {
        this.service = service;
    }

    /** 分页读取指定不可变版本的可见文档元数据。 */
    @GetMapping
    public Result<PageResult<KnowledgeVersionDocumentVO>> documents(
        @PathVariable Long knowledgeBaseId, @PathVariable Long versionId,
        @RequestParam(defaultValue = "1") int pageNum, HttpServletResponse response) {
        requirePositive(knowledgeBaseId, versionId, pageNum);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return Result.success(service.documents(knowledgeBaseId, versionId, pageNum, identity()));
    }

    /** 获取指定成员的历史正文，读取失败或授权变化不回退到任何其他版本。 */
    @GetMapping("/{revisionId}/preview")
    public Result<KnowledgeDocumentPreviewVO> preview(
        @PathVariable Long knowledgeBaseId, @PathVariable Long versionId,
        @PathVariable Long revisionId, HttpServletResponse response) {
        requirePositive(knowledgeBaseId, versionId, revisionId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return Result.success(service.preview(knowledgeBaseId, versionId, revisionId, identity()));
    }

    private void requirePositive(long... values) {
        if (Arrays.stream(values).anyMatch(value -> value < 1)) {
            throw new BizException(ResultCode.PARAM_INVALID, "版本、文档标识和页码必须为正整数");
        }
    }

    private AgentInvocationIdentity identity() {
        return new AgentInvocationIdentity(TenantContext.require(), QuotaSubjectType.ADMIN_USER,
            StpUtil.getLoginIdAsString(), true).withChannel(AgentInvocationIdentity.CHANNEL_ADMIN);
    }
}
