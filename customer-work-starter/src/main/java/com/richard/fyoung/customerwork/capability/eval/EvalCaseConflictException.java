package com.richard.fyoung.customerwork.capability.eval;

/** 新建评测用例的编号冲突；保留 IllegalStateException 兼容性并区别于存储故障。 */
public class EvalCaseConflictException extends IllegalStateException {

    public EvalCaseConflictException(String message) {
        super(message);
    }

    public EvalCaseConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
