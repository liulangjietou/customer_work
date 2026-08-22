package com.richard.fyoung.customerwork.core.memory;

/** 长期记忆隐私擦除未全部完成；调用方不得把该异常翻译成“删除成功”。 */
public class MemoryErasureException extends RuntimeException {

    public MemoryErasureException(Throwable cause) {
        super("memory erasure did not complete", cause);
    }
}
