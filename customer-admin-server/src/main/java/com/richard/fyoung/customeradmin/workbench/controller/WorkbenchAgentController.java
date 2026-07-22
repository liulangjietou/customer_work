package com.richard.fyoung.customeradmin.workbench.controller;

import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchAgentSiteVO;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchSiteService;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 供 ScriptCat 通用脚本回调的凭证接口：**不走 Sa-Token 登录态，改用个人访问令牌鉴权**
 * （脚本运行在目标站点页面里，拿不到后台登录态）。路径已在 {@code SaTokenConfig} 排除。
 *
 * <p>返回明文密码属敏感操作，每次读取都手动落一条操作日志（谁的令牌、取了哪个 host）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workbench/agent")
public class WorkbenchAgentController {

    private static final int RESULT_SUCCESS = 1;

    private final WorkbenchTokenService tokenService;
    private final WorkbenchSiteService siteService;
    private final OperationLogMapper operationLogMapper;

    public WorkbenchAgentController(WorkbenchTokenService tokenService,
                                    WorkbenchSiteService siteService,
                                    OperationLogMapper operationLogMapper) {
        this.tokenService = tokenService;
        this.siteService = siteService;
        this.operationLogMapper = operationLogMapper;
    }

    @GetMapping("/site")
    public Result<WorkbenchAgentSiteVO> site(@RequestParam String host,
                                             @RequestHeader("X-Workbench-Token") String token,
                                             HttpServletRequest request) {
        Long userId = tokenService.validate(token);
        WorkbenchAgentSiteVO vo = siteService.findAgentSiteByHost(host);
        audit(userId, host, request);
        return Result.success(vo);
    }

    /** 记录脚本读取密码的审计日志（只记 host 与令牌所属用户，不记密码）。 */
    private void audit(Long userId, String host, HttpServletRequest request) {
        SysOperationLog log = new SysOperationLog();
        log.setUserId(userId);
        log.setOperation("脚本读取站点密码 host=" + host);
        log.setMethod("GET /api/workbench/agent/site");
        log.setTarget("workbench_site");
        log.setResult(RESULT_SUCCESS);
        log.setIp(request.getRemoteAddr());
        log.setCreateTime(LocalDateTime.now());
        operationLogMapper.insert(log);
    }
}
