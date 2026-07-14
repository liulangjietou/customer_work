package com.richard.fyoung.customeradmin.sqlconfig.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDatasourceSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDatasourceVO;
import com.richard.fyoung.customeradmin.sqlconfig.service.SqlDatasourceService;
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
import java.util.concurrent.CompletableFuture;

/**
 * SQL 配置数据源管理：CRUD + 分页/搜索/筛选 + 连通性测试。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/sql/datasource")
public class SqlDatasourceController {

    private final SqlDatasourceService datasourceService;

    public SqlDatasourceController(SqlDatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    @SaCheckPermission("sql-datasource:view")
    @GetMapping
    public Result<PageResult<SqlDatasourceVO>> page(PageQuery query) {
        return Result.success(datasourceService.page(query));
    }

    /** SQL 定义表单下拉也需读数据源，故放宽为 datasource:view 或 define:view 任一即可。 */
    @SaCheckPermission(value = {"sql-datasource:view", "sql-define:view"}, mode = SaMode.OR)
    @GetMapping("/all")
    public Result<List<SqlDatasourceVO>> all() {
        return Result.success(datasourceService.listEnabled());
    }

    @SaCheckPermission("sql-datasource:view")
    @GetMapping("/{id}")
    public Result<SqlDatasourceVO> get(@PathVariable Long id) {
        return Result.success(datasourceService.get(id));
    }

    @SaCheckPermission("sql-datasource:add")
    @OperationLog(operation = "新建SQL数据源", target = "sql_datasource")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody SqlDatasourceSaveRequest request) {
        datasourceService.create(request);
        return Result.success();
    }

    @SaCheckPermission("sql-datasource:edit")
    @OperationLog(operation = "编辑SQL数据源", target = "sql_datasource")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SqlDatasourceSaveRequest request) {
        datasourceService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("sql-datasource:delete")
    @OperationLog(operation = "删除SQL数据源", target = "sql_datasource")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasourceService.delete(id);
        return Result.success();
    }

    /** 只读探测，复用 view 权限点（不新增权限点，与项目惯例一致）；异步返回释放请求线程。 */
    @SaCheckPermission("sql-datasource:view")
    @OperationLog(operation = "SQL数据源连通性测试", target = "sql_datasource")
    @PostMapping("/{id}/test")
    public CompletableFuture<Result<Void>> test(@PathVariable Long id) {
        return datasourceService.testConnection(id);
    }
}
