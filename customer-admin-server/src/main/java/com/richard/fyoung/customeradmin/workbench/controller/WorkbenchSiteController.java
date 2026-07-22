package com.richard.fyoung.customeradmin.workbench.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteSaveRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteVO;
import com.richard.fyoung.customeradmin.workbench.service.WorkbenchSiteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内网工作台站点管理：CRUD + 分页/搜索 + 明文密码按需读取（供前端复制）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workbench/site")
public class WorkbenchSiteController {

    private final WorkbenchSiteService siteService;

    public WorkbenchSiteController(WorkbenchSiteService siteService) {
        this.siteService = siteService;
    }

    @SaCheckPermission("workbench-site:view")
    @GetMapping
    public Result<PageResult<WorkbenchSiteVO>> page(PageQuery query) {
        return Result.success(siteService.page(query));
    }

    @SaCheckPermission("workbench-site:add")
    @OperationLog(operation = "新建内网工作台站点", target = "workbench_site")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody WorkbenchSiteSaveRequest request) {
        siteService.create(request);
        return Result.success();
    }

    @SaCheckPermission("workbench-site:edit")
    @OperationLog(operation = "编辑内网工作台站点", target = "workbench_site")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody WorkbenchSiteSaveRequest request) {
        siteService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("workbench-site:delete")
    @OperationLog(operation = "删除内网工作台站点", target = "workbench_site")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        siteService.delete(id);
        return Result.success();
    }

    /** 敏感读：返回解密后的明文密码供前端复制，复用 view 权限点并审计。 */
    @SaCheckPermission("workbench-site:view")
    @OperationLog(operation = "查看内网工作台站点密码", target = "workbench_site")
    @GetMapping("/{id}/secret")
    public Result<String> secret(@PathVariable Long id) {
        return Result.success(siteService.getSecret(id));
    }
}
