package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.aiconfig.systemtool.tool.SystemToolHttpGuard;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 开发者工具箱 HTTP 请求工具：代理前端发起真实 HTTP(S) 调用（浏览器直连会被目标站 CORS 拦截，
 * 故由后端出网）。
 *
 * <p>用 JDK 内建 {@link HttpClient} 而非 RestTemplate：智能体侧 httpclient 工具用的
 * {@code SimpleClientHttpRequestFactory} 基于 HttpURLConnection，不支持 PATCH 方法，
 * 而本工具要求覆盖常用七种方法。</p>
 *
 * <p>安全说明（与智能体 httpclient 工具同一套收口）：SSRF 面复用唯一防御点
 * {@link SystemToolHttpGuard}（默认拒内网/环回、放公网，配 {@code admin.system-tool.http.allowed-hosts}
 * 白名单后仅放行白名单内 host）；配套**禁止自动跟随重定向**（{@link HttpClient.Redirect#NEVER}），
 * 3xx 作为终态结果返回并带回 Location——否则"合规公网域名 302 指向内网地址"即可绕过校验。
 * TLS 走 JDK 默认信任链校验，不做 trust-all。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DevToolHttpService {

    private static final Logger log = LoggerFactory.getLogger(DevToolHttpService.class);

    private static final String ERROR_CODE_SEND_FAIL = "DEVTOOL-HTTP-SEND-FAIL";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 单次请求（含读取完整响应）总时限。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** 响应体展示上限，超出部分截断（调试工具按文本展示，不承载大文件下载）。 */
    private static final int MAX_RESPONSE_BODY_BYTES = 1_048_576;

    /** 不允许发送请求体的方法（语义上无 body，且部分服务端会拒绝）。 */
    private static final Set<String> NO_BODY_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * JDK HttpClient 禁止业务设置的请求头（由客户端自动生成，设置会抛 IllegalArgumentException），
     * 组装时静默跳过，与主流调试工具（Postman 等对 auto header）的行为一致。
     */
    private static final Set<String> RESTRICTED_HEADERS =
        Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final SystemToolHttpGuard httpGuard;
    private final HttpClient httpClient;

    public DevToolHttpService(SystemToolHttpGuard httpGuard) {
        this.httpGuard = httpGuard;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * 发起一次 HTTP(S) 调用并返回执行结果；目标服务不可达/超时等收敛进 error 字段，
     * SSRF 拦截与参数非法则 fast fail 抛业务异常。
     */
    public DevToolHttpSendResponse send(DevToolHttpSendRequest request) {
        String url = request.getUrl().trim();
        checkScheme(url);
        // SSRF 收口：发起请求前统一校验目标地址（复用系统工具链路的唯一防御点），命中安全策略 fast fail
        httpGuard.checkAllowed(url);

        HttpRequest httpRequest = buildRequest(request, url);
        long start = System.currentTimeMillis();
        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            return buildResponse(response, System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("devtool http send interrupted, code={}, method={}, url={}",
                ERROR_CODE_SEND_FAIL, request.getMethod(), url, e);
            return failure("请求被中断", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("devtool http send failed, code={}, method={}, url={}",
                ERROR_CODE_SEND_FAIL, request.getMethod(), url, e);
            return failure(describeFailure(e), System.currentTimeMillis() - start);
        }
    }

    /** 仅放行 http/https：file/ftp 等协议既无调试意义，JDK HttpClient 也不支持。 */
    private void checkScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new BizException(ResultCode.PARAM_INVALID, "仅支持 http/https 协议的 URL");
        }
    }

    private HttpRequest buildRequest(DevToolHttpSendRequest request, String url) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "URL 格式非法: " + e.getMessage());
        }

        if (!CollectionUtils.isEmpty(request.getHeaders())) {
            for (DevToolHttpSendRequest.HeaderItem item : request.getHeaders()) {
                String name = item.getName().trim();
                if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    builder.header(name, item.getValue() == null ? "" : item.getValue());
                } catch (IllegalArgumentException e) {
                    throw new BizException(ResultCode.PARAM_INVALID, "请求头不合法: " + name);
                }
            }
        }

        String method = request.getMethod();
        HttpRequest.BodyPublisher bodyPublisher =
            !NO_BODY_METHODS.contains(method) && StringUtils.hasText(request.getBody())
                ? HttpRequest.BodyPublishers.ofString(request.getBody(), StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        return builder.method(method, bodyPublisher).build();
    }

    private DevToolHttpSendResponse buildResponse(HttpResponse<byte[]> response, long durationMs) {
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        boolean truncated = bytes.length > MAX_RESPONSE_BODY_BYTES;
        String bodyText = new String(bytes, 0, Math.min(bytes.length, MAX_RESPONSE_BODY_BYTES), StandardCharsets.UTF_8);
        return DevToolHttpSendResponse.builder()
            .statusCode(response.statusCode())
            .headers(response.headers().map())
            .body(bodyText)
            .bodyBytes((long) bytes.length)
            .bodyTruncated(truncated)
            .durationMs(durationMs)
            .redirectLocation(response.headers().firstValue("location").orElse(null))
            .build();
    }

    private DevToolHttpSendResponse failure(String error, long durationMs) {
        return DevToolHttpSendResponse.builder().error(error).durationMs(durationMs).build();
    }

    /** 把常见网络异常翻译成用户可读的失败原因（异常类型名对非开发者不友好）。 */
    private String describeFailure(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "请求超时（超过 " + REQUEST_TIMEOUT.getSeconds() + " 秒未完成）";
        }
        if (e instanceof java.net.ConnectException) {
            return "连接目标服务失败: " + e.getMessage();
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败（证书校验不通过或协议不匹配）: " + e.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
