package com.richard.fyoung.customeradmin.workbench.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreateRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreatedVO;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenVO;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchTokenService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内网工作台个人访问令牌管理：供用户为 ScriptCat 脚本签发/吊销令牌。
 * 令牌绑定当前登录用户，明文只在创建时返回一次。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workbench/token")
public class WorkbenchTokenController {

    private final WorkbenchTokenService tokenService;

    public WorkbenchTokenController(WorkbenchTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @SaCheckPermission("workbench-site:view")
    @GetMapping
    public Result<List<WorkbenchTokenVO>> list() {
        return Result.success(tokenService.listByUser(StpUtil.getLoginIdAsLong()));
    }

    @SaCheckPermission("workbench-site:view")
    @OperationLog(operation = "创建内网工作台令牌", target = "workbench_token")
    @PostMapping
    public Result<WorkbenchTokenCreatedVO> create(@Valid @RequestBody WorkbenchTokenCreateRequest request) {
        return Result.success(tokenService.createToken(StpUtil.getLoginIdAsLong(), request));
    }

    @SaCheckPermission("workbench-site:view")
    @OperationLog(operation = "吊销内网工作台令牌", target = "workbench_token")
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        tokenService.revoke(StpUtil.getLoginIdAsLong(), id);
        return Result.success();
    }
}
