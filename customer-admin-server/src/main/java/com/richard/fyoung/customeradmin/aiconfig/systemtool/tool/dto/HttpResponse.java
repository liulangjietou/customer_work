package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool.dto;

import lombok.Builder;
import lombok.Data;

/**
 * HTTP 工具单次请求结果。序列化成 JSON 字符串回传给 LLM：正常时带 {@code statusCode}/{@code body}，
 * 异常时（连不上/超时等）{@code error} 非空且 {@code statusCode} 为空——工具永远返回结果而非抛异常，
 * 让 LLM 能读到失败原因继续决策。
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
public class HttpResponse {
    private String url;
    private String method;
    private Integer statusCode;
    private String body;
    private String error;
}
