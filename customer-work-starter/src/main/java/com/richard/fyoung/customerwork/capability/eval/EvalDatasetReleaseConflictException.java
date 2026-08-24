package com.richard.fyoung.customerwork.capability.eval;

/** 命名数据集版本的业务唯一键冲突；与快照损坏等内部状态错误明确区分。 */
public class EvalDatasetReleaseConflictException extends RuntimeException {

    public EvalDatasetReleaseConflictException(String message) {
        super(message);
    }
}
