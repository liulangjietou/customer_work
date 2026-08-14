package com.richard.fyoung.customeradmin.eval.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.eval.service.EvalAdminService;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测：运行历史、单次详情、与上一版对比、立即触发。
 *
 * <p>数据存在客服端库、评测也跑在客服端；本控制器只是运营侧的入口。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    /** 历史默认返回条数：够画一条趋势线，又不至于一次拖回全部历史。 */
    private static final int DEFAULT_RECENT_LIMIT = 20;

    private final EvalAdminService evalAdminService;

    public EvalController(EvalAdminService evalAdminService) {
        this.evalAdminService = evalAdminService;
    }

    /** 某类型最近若干次运行（时间倒序），趋势线与列表用。 */
    @SaCheckPermission("eval:view")
    @GetMapping("/runs")
    public Result<List<EvalRun>> recent(@RequestParam EvalType type,
                                        @RequestParam(defaultValue = "" + DEFAULT_RECENT_LIMIT) int limit) {
        return Result.success(evalAdminService.recent(type, limit));
    }

    /** 单次运行详情，含失败明细与完整原始指标。 */
    @SaCheckPermission("eval:view")
    @GetMapping("/runs/{runId}")
    public Result<EvalRun> detail(@PathVariable String runId) {
        return Result.success(evalAdminService.detail(runId));
    }

    /** 与上一版的对比：指标变化 + 回归/修复用例。 */
    @SaCheckPermission("eval:view")
    @GetMapping("/runs/{runId}/comparison")
    public Result<EvalComparison> comparison(@PathVariable String runId) {
        return Result.success(evalAdminService.comparison(runId));
    }

    /**
     * 立即跑一次评测。
     *
     * <p>转发到客服端执行——评测要评的是线上真实在跑的那一套，后台自己评等于评了个副本。
     * QUALITY 类型逐条调模型，耗时按分钟计且有真实 token 成本。</p>
     */
    @SaCheckPermission("eval:run")
    @OperationLog(operation = "触发评测", target = "cw_eval_run")
    @PostMapping("/run")
    public Result<EvalComparison> run(@RequestParam EvalType type,
                                      @RequestParam(required = false) String remark) {
        return Result.success(evalAdminService.trigger(type, remark));
    }
}
