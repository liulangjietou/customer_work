package com.richard.fyoung.customeradmin.aiconfig.agent.publication;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 发布检查读取评测详情，因此同时要求智能体与评测查看权限。 */
@RestController
@RequestMapping("/api/aiconfig/agent/{agentId}/publication-check")
public class AgentPublicationCheckController {
    private final AgentPublicationCheckService service;

    public AgentPublicationCheckController(AgentPublicationCheckService service) { this.service = service; }

    /** 登录与权限变化后必须重新读取服务端事实，个人回执和发布检查不允许缓存。 */
    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    /** 汇总正式配置、门禁与实例确认；本请求不产生模型或发布调用。 */
    @GetMapping
    @SaCheckPermission({"agent:view", "eval:view"})
    public Result<AgentPublicationCheck> check(@PathVariable long agentId) {
        return Result.success(service.check(agentId));
    }
}
