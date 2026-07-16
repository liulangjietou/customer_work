package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool.dto;

import lombok.Builder;
import lombok.Data;

/**
 * HTTP 工具单次请求结果。序列化成 JSON 字符串回传给 LLM：正常时带 {@code statusCode}/{@code body}，
 * 异常时（连不上/超时等）{@code error} 非空且 {@code statusCode} 为空——工具永远返回结果而非抛异常，
 * 让 LLM 能读到失败原因继续决策。
 *
 * <p>工具不自动跟随重定向（SSRF 收口配套约束）：3xx 也是终态结果，此时 {@code location} 带回
 * Location 响应头，LLM 决定是否对下一跳再发一次工具调用（会重新过一遍安全校验）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
public class HttpResponse {
    private String url;
    private String method;
    private Integer statusCode;
    private String body;
    /** 3xx 重定向响应的 Location 头（工具不自动跟随，交由调用方决策下一跳）。 */
    private String location;
    private String error;
}
