package com.richard.fyoung.customeradmin.contentguard.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordPageQuery;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordVO;
import com.richard.fyoung.customeradmin.contentguard.service.SensitiveWordService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 敏感词词库管理：CRUD + 分页/筛选 + 启停 + 批量导入导出 + 枚举下拉。
 *
 * <p>词库落在客服端库，改动经 starter 侧轮询自动生效（默认 60 秒内），后台无需知道任何客服实例地址。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/contentguard/sensitive-word")
public class SensitiveWordController {

    private final SensitiveWordService sensitiveWordService;
    private final CrossTenantAuthority crossTenantAuthority;

    public SensitiveWordController(SensitiveWordService sensitiveWordService,
                                   CrossTenantAuthority crossTenantAuthority) {
        this.sensitiveWordService = sensitiveWordService;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    @SaCheckPermission("sensitive-word:view")
    @GetMapping("/page")
    public Result<PageResult<SensitiveWordVO>> page(SensitiveWordPageQuery query) {
        return Result.success(sensitiveWordService.page(query));
    }

    @SaCheckPermission("sensitive-word:view")
    @GetMapping("/{id}")
    public Result<SensitiveWordVO> get(@PathVariable Long id) {
        return Result.success(sensitiveWordService.get(id));
    }

    /** 类目下拉，权限点复用 view。 */
    @SaCheckPermission("sensitive-word:view")
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        return Result.success(sensitiveWordService.categories());
    }

    /** 处置动作下拉，权限点复用 view。 */
    @SaCheckPermission("sensitive-word:view")
    @GetMapping("/actions")
    public Result<List<String>> actions() {
        return Result.success(sensitiveWordService.actions());
    }

    @SaCheckPermission("sensitive-word:add")
    @OperationLog(operation = "新增敏感词", target = "cw_sensitive_word")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody SensitiveWordSaveRequest request) {
        requireSafeGlobalWrite();
        sensitiveWordService.create(request);
        return Result.success();
    }

    @SaCheckPermission("sensitive-word:edit")
    @OperationLog(operation = "编辑敏感词", target = "cw_sensitive_word")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SensitiveWordSaveRequest request) {
        requireSafeGlobalWrite();
        sensitiveWordService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("sensitive-word:edit")
    @OperationLog(operation = "启停敏感词", target = "cw_sensitive_word")
    @PutMapping("/{id}/enabled")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        requireSafeGlobalWrite();
        sensitiveWordService.toggle(id, enabled);
        return Result.success();
    }

    @SaCheckPermission("sensitive-word:delete")
    @OperationLog(operation = "删除敏感词", target = "cw_sensitive_word")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireSafeGlobalWrite();
        sensitiveWordService.delete(id);
        return Result.success();
    }

    /** 批量导入：每行 {@code 词面,类目,动作}（类目/动作可省）。返回实际处理条数。 */
    @SaCheckPermission("sensitive-word:add")
    @OperationLog(operation = "批量导入敏感词", target = "cw_sensitive_word")
    @PostMapping("/import")
    public Result<Integer> importWords(@RequestBody List<String> lines) {
        requireSafeGlobalWrite();
        return Result.success(sensitiveWordService.importWords(lines));
    }

    /** 导出全部词条（导入同格式），供运营备份与迁移。 */
    @SaCheckPermission("sensitive-word:view")
    @GetMapping("/export")
    public Result<List<String>> exportWords() {
        return Result.success(sensitiveWordService.exportWords());
    }

    /**
     * 当前客服端过滤器会合并所有租户的启用词，写入会影响全局运行时；在完成按租户分片前，
     * 写接口必须收敛到控制面，避免普通租户通过高频单字阻断其它租户。
     */
    private void requireSafeGlobalWrite() {
        crossTenantAuthority.requireCurrentUserAuthority();
    }
}
