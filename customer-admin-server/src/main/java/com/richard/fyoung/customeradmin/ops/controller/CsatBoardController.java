package com.richard.fyoung.customeradmin.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ops.service.OpsAdminService;
import com.richard.fyoung.customerwork.capability.csat.CsatSummary;
import com.richard.fyoung.customerwork.capability.csat.CsatSurvey;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * CSAT 运营看板：会话级满意度指标与原始评价。
 *
 * <p>与消息级点赞/点踩衡量的是完全不同的东西：那个看单句质量，这个看"这次服务整体解决了没有"。
 * 一次会话可能每句都答得像样但问题始终没解决——那会拿到一堆 UP 和一个 2 分。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/ops/csat")
public class CsatBoardController {

    /** 默认窗口最近 7 天：CSAT 是按周看的指标，按天看噪声太大。 */
    private static final long DEFAULT_WINDOW_MS = Duration.ofDays(7).toMillis();

    private final OpsAdminService opsAdminService;

    public CsatBoardController(OpsAdminService opsAdminService) {
        this.opsAdminService = opsAdminService;
    }

    /**
     * 汇总指标。
     *
     * <p>{@code csat} 与 {@code responseRate} 必须一起看：回收率低时，那个漂亮的满意度
     * 只代表愿意评价的一小撮人——特别满意和特别不满的两头，沉默的大多数不在样本里。</p>
     */
    @SaCheckPermission("csat:view")
    @GetMapping("/summary")
    public Result<CsatSummary> summary(@RequestParam(defaultValue = TenantContext.DEFAULT) String scopeId,
                                       @RequestParam(required = false) Long windowStartMs,
                                       @RequestParam(required = false) Long windowEndMs) {
        long end = windowEndMs != null ? windowEndMs : System.currentTimeMillis();
        long start = windowStartMs != null ? windowStartMs : end - DEFAULT_WINDOW_MS;
        return Result.success(opsAdminService.csatSummary(scopeId, start, end));
    }

    /** 窗口内的原始评价（含低分留言——那才是能拿来改进的东西）。 */
    @SaCheckPermission("csat:view")
    @GetMapping("/list")
    public Result<List<CsatSurvey>> list(@RequestParam(defaultValue = TenantContext.DEFAULT) String scopeId,
                                         @RequestParam(required = false) Long windowStartMs,
                                         @RequestParam(required = false) Long windowEndMs) {
        long end = windowEndMs != null ? windowEndMs : System.currentTimeMillis();
        long start = windowStartMs != null ? windowStartMs : end - DEFAULT_WINDOW_MS;
        return Result.success(opsAdminService.csatSurveys(scopeId, start, end));
    }
}
