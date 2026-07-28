package com.richard.fyoung.customeradmin.contentguard.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.contentguard.dto.RateLimitRuleSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.dto.RateLimitRuleVO;
import com.richard.fyoung.customeradmin.contentguard.service.RateLimitRuleService;
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
 * 限流规则管理：CRUD + 分页/筛选 + 启停 + 枚举下拉。
 *
 * <p>规则落在客服端库，starter 侧轮询指纹自动换快照（默认 60 秒内生效）。
 * 规则只在客服端开启 {@code customer-work.security.rate-limit.rule-enabled=true} 且
 * {@code store-mode=jdbc} 时才被读取——后台能配不等于一定在跑，这点要在页面上讲清楚。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/contentguard/rate-limit-rule")
public class RateLimitRuleController {

    private final RateLimitRuleService rateLimitRuleService;

    public RateLimitRuleController(RateLimitRuleService rateLimitRuleService) {
        this.rateLimitRuleService = rateLimitRuleService;
    }

    @SaCheckPermission("rate-limit-rule:view")
    @GetMapping("/page")
    public Result<PageResult<RateLimitRuleVO>> page(PageQuery query) {
        return Result.success(rateLimitRuleService.page(query));
    }

    @SaCheckPermission("rate-limit-rule:view")
    @GetMapping("/{id}")
    public Result<RateLimitRuleVO> get(@PathVariable Long id) {
        return Result.success(rateLimitRuleService.get(id));
    }

    /** 计数维度下拉，权限点复用 view。 */
    @SaCheckPermission("rate-limit-rule:view")
    @GetMapping("/dimensions")
    public Result<List<String>> dimensions() {
        return Result.success(rateLimitRuleService.dimensions());
    }

    /** 限流算法下拉，权限点复用 view。 */
    @SaCheckPermission("rate-limit-rule:view")
    @GetMapping("/algorithms")
    public Result<List<String>> algorithms() {
        return Result.success(rateLimitRuleService.algorithms());
    }

    @SaCheckPermission("rate-limit-rule:add")
    @OperationLog(operation = "新增限流规则", target = "cw_rate_limit_rule")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody RateLimitRuleSaveRequest request) {
        rateLimitRuleService.create(request);
        return Result.success();
    }

    @SaCheckPermission("rate-limit-rule:edit")
    @OperationLog(operation = "编辑限流规则", target = "cw_rate_limit_rule")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RateLimitRuleSaveRequest request) {
        rateLimitRuleService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("rate-limit-rule:edit")
    @OperationLog(operation = "启停限流规则", target = "cw_rate_limit_rule")
    @PutMapping("/{id}/enabled")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        rateLimitRuleService.toggle(id, enabled);
        return Result.success();
    }

    @SaCheckPermission("rate-limit-rule:delete")
    @OperationLog(operation = "删除限流规则", target = "cw_rate_limit_rule")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        rateLimitRuleService.delete(id);
        return Result.success();
    }
}
