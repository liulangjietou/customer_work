package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftIds;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentDraftService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 试用身份由登录和租户上下文派生，草稿归属及新建/编辑权限在唯一 HTTP 入口检查。 */
@Validated
@RestController
@RequestMapping("/api/aiconfig/agent-drafts/{draftId}/trials")
@SaCheckPermission("agent:view")
public class AgentDraftTrialController {
    private final AgentDraftService drafts;
    private final AgentDraftTrialService trials;

    public AgentDraftTrialController(AgentDraftService drafts, AgentDraftTrialService trials) {
        this.drafts = drafts;
        this.trials = trials;
    }

    /** 登录与权限变化后必须重新读取服务端事实，个人回执和发布检查不允许缓存。 */
    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    /** 预检不会执行模型；前端显示实际可试用范围。 */
    @GetMapping("/preview")
    public Result<AgentDraftTrialService.Preview> preview(
        @PathVariable @Pattern(regexp = AgentDraftIds.UUID_PATTERN) String draftId) {
        return Result.success(trials.preview(owned(draftId)));
    }

    /** 只读历史不占用模型调用额度。 */
    @GetMapping
    public Result<List<AgentDraftTrialSummary>> list(
        @PathVariable @Pattern(regexp = AgentDraftIds.UUID_PATTERN) String draftId) {
        return Result.success(trials.list(owned(draftId), StpUtil.getLoginIdAsLong()));
    }

    /** 持久回执按原标识恢复。 */
    @GetMapping("/{trialId}")
    public Result<AgentDraftTrialView> get(
        @PathVariable @Pattern(regexp = AgentDraftIds.UUID_PATTERN) String draftId,
        @PathVariable @Pattern(regexp = AgentDraftIds.UUID_PATTERN) String trialId) {
        return Result.success(trials.get(owned(draftId), StpUtil.getLoginIdAsLong(), trialId));
    }

    /** 唯一受理方执行，重复请求仅查已有回执。 */
    @PutMapping("/{trialId}")
    public Result<AgentDraftTrialView> start(
        @PathVariable @Pattern(regexp = AgentDraftIds.UUID_PATTERN) String draftId,
        @PathVariable @Pattern(regexp = AgentDraftIds.UUID_PATTERN) String trialId,
        @Valid @RequestBody AgentDraftTrialRequest request) {
        long owner = StpUtil.getLoginIdAsLong();
        var identity = AgentInvocationIdentity.capture();
        if (identity == null || !identity.authenticated() || identity.subjectType() != QuotaSubjectType.ADMIN_USER
            || !String.valueOf(owner).equals(identity.subjectId()) || !TenantContext.require().equals(identity.tenantId())) {
            throw new BizException(ResultCode.FORBIDDEN, "缺少一致的试用登录身份");
        }
        return Result.success(trials.start(owned(draftId), owner, trialId, request, identity));
    }

    private AgentDraftVO owned(String draftId) {
        var draft = drafts.get(draftId, StpUtil.getLoginIdAsLong());
        StpUtil.checkPermission(draft.agentId() == null ? "agent:add" : "agent:edit");
        return draft;
    }
}
