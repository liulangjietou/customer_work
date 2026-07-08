package com.richard.fyoung.customeradmin.system.log.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.service.OperationLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询（只读）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/system/log")
public class OperationLogController {

    private final OperationLogService operationLogService;

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @SaCheckPermission("log:view")
    @GetMapping
    public Result<PageResult<SysOperationLog>> page(PageQuery query) {
        return Result.success(operationLogService.page(query));
    }
}
