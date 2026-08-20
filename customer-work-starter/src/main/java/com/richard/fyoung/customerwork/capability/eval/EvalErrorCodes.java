package com.richard.fyoung.customerwork.capability.eval;

/**
 * 评测链路的日志错误码。
 *
 * @author owlzhangfq@gmail.com
 */
public final class EvalErrorCodes {

    /** 评测数据集加载失败（意图与质量两个 Runner 共用同一个码，便于一次捞全）。 */
    public static final String LOAD_FAIL = "EVAL-LOAD-FAIL";

    private EvalErrorCodes() {
    }
}
