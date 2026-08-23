package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.time.LocalDateTime;

/**
 * 模型连通性测试结果。
 *
 * @param testStatus 取值见 {@link com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus}
 * @param testTime   本次测试时间
 * @param message    失败原因（成功时为 null）
 * @author owlzhangfq@gmail.com
 */
public record ModelTestResult(int testStatus,
                              LocalDateTime testTime,
                              String message,
                              String healthStatus,
                              String errorCategory,
                              Long latencyMs) {

    /** 兼容既有探活工厂；治理字段由 ModelHealthService 补齐。 */
    public ModelTestResult(int testStatus, LocalDateTime testTime, String message) {
        this(testStatus, testTime, message, null, null, null);
    }
}
