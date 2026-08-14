package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时基线评测：每天自动跑一遍意图标准集，攒出可回溯的趋势线。
 *
 * <p>人工触发的评测只在"我记得要评"的时候发生，而指标劣化往往是慢慢滑下去的——
 * 等到有人想起来跑一次，已经分不清是哪次改动引入的。定时基线的价值就在于把时间轴填满，
 * 让每次改动前后都有一个天然的对照点。</p>
 *
 * <p>只跑意图评测：离线确定性、零 token 成本，可以放心无人值守；质量评测每次都有真实开销，
 * 留给显式触发。默认关闭（{@code eval.baseline-enabled=false}），生产显式打开。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class EvalBaselineScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvalBaselineScheduler.class);

    private static final String BASELINE_REMARK = "scheduled baseline";

    private final CustomerWorkProperties properties;
    private final EvalService evalService;

    public EvalBaselineScheduler(CustomerWorkProperties properties, EvalService evalService) {
        this.properties = properties;
        this.evalService = evalService;
    }

    @Scheduled(cron = "${customer-work.eval.baseline-cron:0 0 3 * * ?}")
    public void runBaseline() {
        if (!properties.getEval().isBaselineEnabled()) {
            return;
        }
        try {
            EvalComparison comparison = evalService.runIntent(EvalTrigger.SCHEDULED, BASELINE_REMARK);
            // 回归项单独记一条：总分持平时它是唯一的异常信号，混在一行里容易被日志巡检漏掉
            if (!comparison.regressions().isEmpty()) {
                log.error("scheduled eval found regressions, errorCode={}, runId={}, cases={}",
                    "EVAL-BASELINE-REGRESSION", comparison.current().runId(), comparison.regressions());
            }
            log.info("scheduled eval baseline done: runId={}, verdict={}, primary={}",
                comparison.current().runId(), comparison.verdict(),
                String.format("%.4f", comparison.current().primaryMetric()));
        } catch (Exception e) {
            log.error("scheduled eval baseline failed, errorCode={}", "EVAL-BASELINE-FAIL", e);
        }
    }
}
