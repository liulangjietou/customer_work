package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalService;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 评测端点（运营视角，受 X-API-Key 保护）。
 *
 * <p>评测必须跑在客服端而不是后台：它要的是<b>真实的这一套</b>——同一个 orchestrator、同一份提示词、
 * 同一条模型链。后台另起一套等价实现去评测，评的就不是线上跑的东西了。</p>
 *
 * <p>三类调用方共用这组端点：后台管理页面的"立即评测"按钮、定时基线任务、
 * 以及发布流水线的 CI 门禁（拿 {@code verdict} 与 {@code regressions} 判断是否放行）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/eval")
@Tag(name = "评测", description = "跑标准集、出报告、与上一版对比")
public class EvalController {

    /** 运行历史默认返回条数：够画一条趋势线，又不至于一次拖回全部历史。 */
    private static final int DEFAULT_RECENT_LIMIT = 20;

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @Operation(summary = "跑意图评测标准集",
        description = "离线确定性评测（不调模型），落库并与上一版对比；适合定时跑与 CI 门禁")
    @PostMapping("/intent")
    public Mono<EvalComparison> runIntent(@RequestParam(defaultValue = "API") EvalTrigger trigger,
                                          @RequestParam(required = false) String remark) {
        return Mono.fromCallable(() -> evalService.runIntent(trigger, remark))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "跑回复质量评测标准集",
        description = "LLM-as-Judge 打分，逐条生成回复再评分，有真实 token 成本；需配置模型 Key")
    @PostMapping("/quality")
    public Mono<EvalComparison> runQuality(@RequestParam(defaultValue = "API") EvalTrigger trigger,
                                           @RequestParam(required = false) String remark) {
        return Mono.fromCallable(() -> evalService.runQuality(trigger, remark))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "最近运行记录", description = "按类型取最近若干次运行，时间倒序")
    @GetMapping("/runs")
    public Mono<List<EvalRun>> recent(@RequestParam EvalType type,
                                      @RequestParam(defaultValue = "" + DEFAULT_RECENT_LIMIT) int limit) {
        return Mono.fromCallable(() -> evalService.recent(type, limit))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "单次运行详情")
    @GetMapping("/runs/{runId}")
    public Mono<ResponseEntity<EvalRun>> detail(@PathVariable String runId) {
        return Mono.fromCallable(() -> evalService.find(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build()))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "与上一版的对比", description = "回看某次运行相对它上一版的指标变化与回归用例")
    @GetMapping("/runs/{runId}/comparison")
    public Mono<ResponseEntity<EvalComparison>> comparison(@PathVariable String runId) {
        return Mono.fromCallable(() -> evalService.compareWithBaseline(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build()))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
