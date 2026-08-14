package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 评测能力配置。
 *
 * <p>评测的价值全在纵向对比上——"这版比上版好还是坏"。memory 模式重启即丢基线，
 * 每次运行都退化成孤立的一次性体检，生产务必切 jdbc。</p>
 */
@Data
public class EvalProperties {

    /** 运行记录存储模式：memory（进程内，默认，重启丢基线）| jdbc（落 cw_eval_run，可纵向对比）。 */
    private String storeMode = "memory";

    /**
     * Judge 单次打分超时（秒）。
     *
     * <p>比常规对话超时宽松：Judge 要读完"输入+期望+回复"三段再给结论，输入长于普通问答。</p>
     */
    private int judgeTimeoutSeconds = 60;

    /**
     * 是否开启定时基线评测（默认关）。
     *
     * <p>默认关而不是默认开：开发/测试环境每天自动跑会往历史里塞入大量无意义的基线，
     * 反而稀释了真正有对比价值的那几次运行。生产环境显式打开。</p>
     */
    private boolean baselineEnabled = false;

    /**
     * 定时基线的 cron 表达式，默认每天凌晨 3 点。
     *
     * <p>只跑意图评测（离线确定性、零 token 成本）；质量评测每跑一次都真金白银，
     * 不适合无人值守地定时烧钱，留给显式触发。</p>
     */
    private String baselineCron = "0 0 3 * * ?";
}
