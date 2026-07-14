package com.richard.fyoung.customeradmin.sqlconfig.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineParamSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineParamVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlFieldTransformSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlFieldTransformVO;
import com.richard.fyoung.customeradmin.sqlconfig.service.SqlDefineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL 定义管理：define CRUD + 复制 + 参数/列转换器子资源 CRUD。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/sql/define")
public class SqlDefineController {

    private final SqlDefineService defineService;

    public SqlDefineController(SqlDefineService defineService) {
        this.defineService = defineService;
    }

    // ============================ define ============================

    @SaCheckPermission("sql-define:view")
    @GetMapping
    public Result<PageResult<SqlDefineVO>> page(PageQuery query) {
        return Result.success(defineService.page(query));
    }

    @SaCheckPermission("sql-define:view")
    @GetMapping("/{id}")
    public Result<SqlDefineVO> get(@PathVariable Long id) {
        return Result.success(defineService.get(id));
    }

    @SaCheckPermission("sql-define:add")
    @OperationLog(operation = "新建SQL定义", target = "sql_define")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody SqlDefineSaveRequest request) {
        defineService.create(request);
        return Result.success();
    }

    @SaCheckPermission("sql-define:edit")
    @OperationLog(operation = "编辑SQL定义", target = "sql_define")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SqlDefineSaveRequest request) {
        defineService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("sql-define:delete")
    @OperationLog(operation = "删除SQL定义", target = "sql_define")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        defineService.delete(id);
        return Result.success();
    }

    @SaCheckPermission("sql-define:add")
    @OperationLog(operation = "复制SQL定义", target = "sql_define")
    @PostMapping("/{id}/copy")
    public Result<Void> copy(@PathVariable Long id) {
        defineService.copy(id);
        return Result.success();
    }

    // ============================ 参数子资源 ============================

    @SaCheckPermission("sql-define:view")
    @GetMapping("/{id}/params")
    public Result<List<SqlDefineParamVO>> listParams(@PathVariable Long id) {
        return Result.success(defineService.listParams(id));
    }

    @SaCheckPermission("sql-define:edit")
    @OperationLog(operation = "新建SQL参数", target = "sql_define_param")
    @PostMapping("/{id}/params")
    public Result<Void> createParam(@PathVariable Long id, @Valid @RequestBody SqlDefineParamSaveRequest request) {
        defineService.createParam(id, request);
        return Result.success();
    }

    @SaCheckPermission("sql-define:edit")
    @OperationLog(operation = "编辑SQL参数", target = "sql_define_param")
    @PutMapping("/{id}/params/{paramId}")
    public Result<Void> updateParam(@PathVariable Long id, @PathVariable Long paramId,
                                    @Valid @RequestBody SqlDefineParamSaveRequest request) {
        defineService.updateParam(id, paramId, request);
        return Result.success();
    }

    @SaCheckPermission("sql-define:edit")
    @OperationLog(operation = "删除SQL参数", target = "sql_define_param")
    @DeleteMapping("/{id}/params/{paramId}")
    public Result<Void> deleteParam(@PathVariable Long id, @PathVariable Long paramId) {
        defineService.deleteParam(id, paramId);
        return Result.success();
    }

    // ============================ 列转换器子资源 ============================

    @SaCheckPermission("sql-define:view")
    @GetMapping("/{id}/transforms")
    public Result<List<SqlFieldTransformVO>> listTransforms(@PathVariable Long id) {
        return Result.success(defineService.listTransforms(id));
    }

    @SaCheckPermission("sql-define:edit")
    @OperationLog(operation = "新建SQL列转换器", target = "sql_field_transform")
    @PostMapping("/{id}/transforms")
    public Result<Void> createTransform(@PathVariable Long id, @Valid @RequestBody SqlFieldTransformSaveRequest request) {
        defineService.createTransform(id, request);
        return Result.success();
    }

    @SaCheckPermission("sql-define:edit")
    @OperationLog(operation = "编辑SQL列转换器", target = "sql_field_transform")
    @PutMapping("/{id}/transforms/{transformId}")
    public Result<Void> updateTransform(@PathVariable Long id, @PathVariable Long transformId,
                                        @Valid @RequestBody SqlFieldTransformSaveRequest request) {
        defineService.updateTransform(id, transformId, request);
        return Result.success();
    }

    @SaCheckPermission("sql-define:edit")
    @OperationLog(operation = "删除SQL列转换器", target = "sql_field_transform")
    @DeleteMapping("/{id}/transforms/{transformId}")
    public Result<Void> deleteTransform(@PathVariable Long id, @PathVariable Long transformId) {
        defineService.deleteTransform(id, transformId);
        return Result.success();
    }
}
