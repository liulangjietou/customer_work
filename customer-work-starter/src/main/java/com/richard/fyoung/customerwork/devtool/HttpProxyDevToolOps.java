package com.richard.fyoung.customerwork.devtool;

import com.richard.fyoung.customerwork.safety.security.HttpTargetGuard;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 开发者工具箱 HTTP 请求工具的执行核心：代理调用方发起一次真实 HTTP(S) 请求
 * （浏览器直连会被目标站 CORS 拦截，故由后端出网）。
 *
 * <p>用 JDK 内建 {@link HttpClient} 而非 {@link HttpClientDevToolOps} 的 RestTemplate：后者基于
 * HttpURLConnection，不支持 PATCH，而本工具要覆盖常用七种方法；另外本工具面向人（页面调试），
 * 需要响应头明细、字节数、耗时与截断标记，与面向 LLM 的那套返回契约不同，故两套执行核心并存。</p>
 *
 * <p>无 Spring 依赖、由调用方直接 new（上层只做 VO 转换与异常转译），与同包
 * {@code CertDevToolOps} ↔ admin {@code DevToolCertService} 的分工一致。</p>
 *
 * <p><b>安全</b>：SSRF 面在构造注入的 {@link HttpTargetGuard} 收口（本链路唯一防御点，含协议校验）；
 * 配套<b>禁止自动跟随重定向</b>（{@link HttpClient.Redirect#NEVER}），3xx 作为终态结果返回并带回
 * Location——否则"合规公网域名 302 指向内网地址"即可绕过校验。TLS 走 JDK 默认信任链校验，不做 trust-all。</p>
 *
 * <p><b>异常约定</b>：目标服务不可达/超时/证书错误等收敛进结果的 {@code error} 字段（对调试工具而言
 * 这是要展示给用户的正常结果）；只有"请求根本不该发出"的场景才抛异常——安全策略拦截抛
 * {@link com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException}，参数非法抛
 * {@link IllegalArgumentException}，由调用方分别转译成自己的错误码。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class HttpProxyDevToolOps {

    private static final Logger log = LoggerFactory.getLogger(HttpProxyDevToolOps.class);

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

    private final HttpTargetGuard httpGuard;
    private final HttpClient httpClient;

    public HttpProxyDevToolOps(HttpTargetGuard httpGuard) {
        this.httpGuard = httpGuard;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * 发起一次 HTTP(S) 调用并返回执行结果。
     *
     * @param method  请求方法（大写，如 GET/POST/PATCH）
     * @param rawUrl  目标地址，仅支持 http/https
     * @param headers 请求头列表，允许同名重复；受限头静默跳过
     * @param body    请求体原始文本；{@link #NO_BODY_METHODS} 内的方法忽略该参数
     * @return 执行结果
     */
    public HttpProxyResult send(String method, String rawUrl, List<HeaderPair> headers, String body) {
        String url = StringUtils.hasText(rawUrl) ? rawUrl.trim() : rawUrl;
        // SSRF 收口：发起请求前统一校验目标地址（唯一防御点，含空值/协议校验），命中安全策略 fast fail
        httpGuard.checkAllowed(url);

        HttpRequest httpRequest = buildRequest(method, url, headers, body);
        long start = System.currentTimeMillis();
        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            return buildResult(response, System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("devtool http send interrupted, code={}, method={}, url={}",
                ERROR_CODE_SEND_FAIL, method, url, e);
            return failure("请求被中断", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("devtool http send failed, code={}, method={}, url={}", ERROR_CODE_SEND_FAIL, method, url, e);
            return failure(describeFailure(e), System.currentTimeMillis() - start);
        }
    }

    private HttpRequest buildRequest(String method, String url, List<HeaderPair> headers, String body) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式非法: " + e.getMessage());
        }

        if (!CollectionUtils.isEmpty(headers)) {
            for (HeaderPair header : headers) {
                String name = header.name().trim();
                if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    builder.header(name, header.value() == null ? "" : header.value());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("请求头不合法: " + name);
                }
            }
        }

        HttpRequest.BodyPublisher bodyPublisher =
            !NO_BODY_METHODS.contains(method) && StringUtils.hasText(body)
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        return builder.method(method, bodyPublisher).build();
    }

    private HttpProxyResult buildResult(HttpResponse<byte[]> response, long durationMs) {
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        boolean truncated = bytes.length > MAX_RESPONSE_BODY_BYTES;
        String bodyText = new String(bytes, 0, Math.min(bytes.length, MAX_RESPONSE_BODY_BYTES), StandardCharsets.UTF_8);
        return HttpProxyResult.builder()
            .statusCode(response.statusCode())
            .headers(response.headers().map())
            .body(bodyText)
            .bodyBytes((long) bytes.length)
            .bodyTruncated(truncated)
            .durationMs(durationMs)
            .redirectLocation(response.headers().firstValue("location").orElse(null))
            .build();
    }

    private HttpProxyResult failure(String error, long durationMs) {
        return HttpProxyResult.builder().error(error).durationMs(durationMs).build();
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

    /** 单个请求头键值对（用列表而非 Map：请求头允许同名重复，如多个 Cookie）。 */
    public record HeaderPair(String name, String value) {
    }

    /** 单次代理调用的执行结果（贫血数据袋，由调用方转成自己的 VO）。 */
    @Getter
    @Builder
    public static final class HttpProxyResult {

        /** HTTP 状态码；请求未发出/未收到响应时为 null。 */
        private final Integer statusCode;

        /** 响应头（同名头合并为值列表）。 */
        private final Map<String, List<String>> headers;

        /** 响应体文本（按 UTF-8 解码，超限截断）。 */
        private final String body;

        /** 响应体原始字节数（截断前的真实大小）。 */
        private final Long bodyBytes;

        /** 响应体是否因超过大小上限被截断。 */
        private final boolean bodyTruncated;

        /** 本次请求耗时（毫秒）。 */
        private final long durationMs;

        /** 3xx 响应的 Location 头（工具不自动跟随重定向，由调用方决定是否跟进下一跳）。 */
        private final String redirectLocation;

        /** 失败原因；成功时为 null。 */
        private final String error;
    }
}
