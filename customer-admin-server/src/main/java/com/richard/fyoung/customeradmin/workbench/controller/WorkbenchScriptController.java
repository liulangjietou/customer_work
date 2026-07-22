package com.richard.fyoung.customeradmin.workbench.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreateRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreatedVO;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchSiteService;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchTokenService;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchUserscriptGenerator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生成内嵌个人令牌的 ScriptCat 通用登录脚本。
 *
 * <p>生成时即为用户新签发一个令牌（明文只此刻嵌入脚本一次），随后拼入所有启用站点的
 * {@code @match} 与 {@code @connect}。前端拿到脚本全文后触发浏览器下载，用户拖入 ScriptCat 安装。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workbench/script")
public class WorkbenchScriptController {

    private final WorkbenchTokenService tokenService;
    private final WorkbenchSiteService siteService;
    private final WorkbenchUserscriptGenerator generator;

    public WorkbenchScriptController(WorkbenchTokenService tokenService,
                                     WorkbenchSiteService siteService,
                                     WorkbenchUserscriptGenerator generator) {
        this.tokenService = tokenService;
        this.siteService = siteService;
        this.generator = generator;
    }

    @SaCheckPermission("workbench-site:view")
    @OperationLog(operation = "生成内网工作台登录脚本", target = "workbench_token")
    @PostMapping("/generate")
    public Result<String> generate(@Valid @RequestBody WorkbenchTokenCreateRequest request) {
        WorkbenchTokenCreatedVO created = tokenService.createToken(StpUtil.getLoginIdAsLong(), request);
        String script = generator.generate(created.getToken(), siteService.listEnabledHosts());
        return Result.success(script);
    }
}
