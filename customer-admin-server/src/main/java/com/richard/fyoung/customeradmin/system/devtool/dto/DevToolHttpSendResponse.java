package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 开发者工具箱 HTTP 请求工具的执行结果。
 *
 * <p>"目标服务连不上/超时/证书错误"对调试工具而言是要展示给用户的正常结果而非系统异常，
 * 所以统一收敛进 {@link #error} 字段随 200 返回；只有 SSRF 拦截、参数非法这类"请求根本不该发出"
 * 的场景才走业务异常。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
public class DevToolHttpSendResponse {

    /** HTTP 状态码；请求未发出/未收到响应时为 null。 */
    private Integer statusCode;

    /** 响应头（同名头合并为值列表）。 */
    private Map<String, List<String>> headers;

    /** 响应体文本（按 UTF-8 解码，超限截断）。 */
    private String body;

    /** 响应体原始字节数（截断前的真实大小）。 */
    private Long bodyBytes;

    /** 响应体是否因超过大小上限被截断。 */
    private boolean bodyTruncated;

    /** 本次请求耗时（毫秒）。 */
    private long durationMs;

    /** 3xx 响应的 Location 头（工具不自动跟随重定向，由用户决定是否跟进下一跳）。 */
    private String redirectLocation;

    /** 失败原因；成功时为 null。 */
    private String error;
}
