package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import java.time.LocalDateTime;

/**
 * 知识库连通性测试结果。用固定探测语句真实发一次检索请求，{@code hitCount} 为本次召回条数
 * （0 条也算连通成功——判成功的标准是 HTTP 200 且响应体 {@code code == "OK"}，与召回多寡无关）。
 *
 * @param testStatus 取值见 {@link com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus}
 * @param testTime   本次测试时间
 * @param message    失败原因（成功时为 null）
 * @param hitCount   本次探测召回条数
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeBaseTestResult(int testStatus, LocalDateTime testTime, String message, int hitCount) {
    public static KnowledgeBaseTestResult success(int hitCount) {
        return new KnowledgeBaseTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null, hitCount);
    }

    public static KnowledgeBaseTestResult failed(String message) {
        return new KnowledgeBaseTestResult(ConnectivityTestStatus.FAILED, LocalDateTime.now(), message, 0);
    }
}
