package com.richard.fyoung.customerwork.tool.mcp.contract;

/**
 * 一次契约比对的总体级别。
 *
 * @author owlzhangfq@gmail.com
 */
public enum McpDriftSeverity {

    /** 指纹一致，什么都没变。 */
    NONE,
    /** 变了，但按旧契约发出的调用仍然能成功。 */
    COMPATIBLE,
    /** 变了，且会让既有调用失败。 */
    BREAKING
}
