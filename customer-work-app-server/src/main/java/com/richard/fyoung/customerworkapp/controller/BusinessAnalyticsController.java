package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.analytics.BusinessAnalyticsReport;
import com.richard.fyoung.customerwork.analytics.BusinessAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 业务数据分析端点（运营视角，受 X-API-Key 保护）。
 *
 * <p>聚合审批 / 人机切换 / 质检三个维度的窗口内统计，回答"这段时间业务运转得怎么样"——
 * 与 {@code DiagnosticController} 按 sessionId 聚合单会话故障现场互补。默认窗口为最近 24 小时
 * （在此解析，保持 {@code BusinessAnalyticsService} 本身对时间窗纯函数、确定性、可测）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/analytics")
@Tag(name = "业务数据分析", description = "审批/人机切换/质检三维度窗口内业务统计")
public class BusinessAnalyticsController {

    private static final long DEFAULT_WINDOW_MS = Duration.ofHours(24).toMillis();

    private final BusinessAnalyticsService analyticsService;

    public BusinessAnalyticsController(BusinessAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Operation(summary = "业务数据分析报表",
        description = "按时间窗口聚合审批放行率/平均决策时长、人机切换平均接单结案时长、"
            + "质检失败数与均分(需指定 tenantId)；windowStartMs/windowEndMs 缺省时默认最近 24 小时")
    @GetMapping("/business")
    public Mono<BusinessAnalyticsReport> business(
            @RequestParam(required = false) Long windowStartMs,
            @RequestParam(required = false) Long windowEndMs,
            @RequestParam(required = false) String tenantId) {
        return Mono.fromCallable(() -> {
                long end = windowEndMs != null ? windowEndMs : System.currentTimeMillis();
                long start = windowStartMs != null ? windowStartMs : end - DEFAULT_WINDOW_MS;
                return analyticsService.report(start, end, tenantId);
            })
            .subscribeOn(Schedulers.boundedElastic());
    }
}
