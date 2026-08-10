package com.richard.fyoung.customerwork.devtool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.safety.security.HttpTargetGuard;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

/**
 * 给智能体用的 HTTP 请求工具执行核心（{@code httpclient} 系统工具的实现层）：GET/DELETE/POST/POST-Form/PUT
 * 五种调用，结果统一序列化成 JSON 字符串回给 LLM。
 *
 * <p>无 Spring 依赖、由调用方直接 new（上层只负责 {@code @Tool} 注解的 Schema 壳与响应式包装），
 * 与同包 {@code DevToolboxTools} ↔ {@code *DevToolOps} 的分工一致。</p>
 *
 * <p><b>安全</b>：TLS 走 JDK 默认信任链校验（不做 trust-all / 不关主机名校验）；SSRF 面在构造注入的
 * {@link HttpTargetGuard} 收口——每次发起请求前校验目标地址，是本链路唯一防御点。配套地
 * <b>禁止自动跟随重定向</b>（见 {@link NoRedirectClientHttpRequestFactory}）：3xx 作为终态响应返回
 * （带 statusCode + location），LLM 若要跟进下一跳需再发一次工具调用，自然再过一遍 Guard——
 * 否则"合规公网域名 302 指向内网地址"即可绕过校验。改动重定向行为前务必同步评估这里。</p>
 *
 * <p><b>异常约定</b>：目标不可达/超时等收敛进结果的 {@code error} 字段（工具永远返回结果而非抛异常，
 * 让 LLM 读到失败原因继续决策）；只有安全策略拦截会抛 {@code HttpTargetForbiddenException}，
 * 由调用方转译成自己的错误码。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class HttpClientDevToolOps {

    private static final Logger log = LoggerFactory.getLogger(HttpClientDevToolOps.class);

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String ERROR_CODE_HTTP_FAIL = "DEVTOOL-HTTP-CALL-FAIL";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpTargetGuard httpGuard;

    public HttpClientDevToolOps(HttpTargetGuard httpGuard) {
        this.httpGuard = httpGuard;
        SimpleClientHttpRequestFactory factory = new NoRedirectClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "50");
        this.restTemplate = new RestTemplate(factory);
    }

    /** GET 请求。 */
    public String get(String url, Map<String, String> headers) {
        return execute(url, HttpMethod.GET, buildHeaders(headers), null);
    }

    /** DELETE 请求。 */
    public String delete(String url, Map<String, String> headers) {
        return execute(url, HttpMethod.DELETE, buildHeaders(headers), null);
    }

    /** POST 请求（JSON body）。 */
    public String post(String url, Map<String, String> headers, String body) {
        HttpHeaders h = buildHeaders(headers);
        h.setContentType(MediaType.APPLICATION_JSON);
        return execute(url, HttpMethod.POST, h, body);
    }

    /** PUT 请求（JSON body）。 */
    public String put(String url, Map<String, String> headers, String body) {
        HttpHeaders h = buildHeaders(headers);
        h.setContentType(MediaType.APPLICATION_JSON);
        return execute(url, HttpMethod.PUT, h, body);
    }

    /** POST 请求（form 表单，参数按 {@code k=v&k=v} 拼接）。 */
    public String postForm(String url, Map<String, String> headers, Map<String, Object> forms) {
        HttpHeaders h = buildHeaders(headers);
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        StringBuilder sb = new StringBuilder();
        if (forms != null) {
            for (Map.Entry<String, Object> entry : forms.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("&");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        return execute(url, HttpMethod.POST, h, sb.toString());
    }

    /** 发起一次请求并把结果序列化成 JSON 字符串；除安全拦截外的任何异常都收敛成 error 字段。 */
    private String execute(String url, HttpMethod method, HttpHeaders headers, String body) {
        // SSRF 收口：发起请求前统一校验目标地址（唯一防御点），命中安全策略 fast fail
        httpGuard.checkAllowed(url);
        HttpToolResult result;
        try {
            HttpEntity<String> entity = body != null ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, method, entity, String.class);
            // 3xx 是终态结果（不自动跟随），带回 Location 让调用方决策下一跳
            URI redirectLocation = resp.getHeaders().getLocation();
            result = HttpToolResult.builder()
                .url(url).method(method.name()).statusCode(resp.getStatusCode().value())
                .body(resp.getBody())
                .location(redirectLocation == null ? null : redirectLocation.toString())
                .build();
        } catch (Exception e) {
            log.error("http tool request failed, code={}, url={}, method={}", ERROR_CODE_HTTP_FAIL, url, method, e);
            result = HttpToolResult.builder().url(url).method(method.name()).error(e.getMessage()).build();
        }
        return toJson(result);
    }

    private String toJson(HttpToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("http tool serialize response failed, code={}, url={}",
                ERROR_CODE_HTTP_FAIL, result.getUrl(), e);
            return "{\"url\":\"" + result.getUrl() + "\",\"error\":\"serialize response failed\"}";
        }
    }

    private HttpHeaders buildHeaders(Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        if (headers != null) {
            headers.forEach(h::add);
        }
        return h;
    }

    /**
     * 所有方法统一禁止自动跟随重定向的请求工厂（SSRF 收口的配套约束，不要移除）。
     *
     * <p>Spring 的 {@link SimpleClientHttpRequestFactory#prepareConnection} 默认对 GET 开
     * {@code setInstanceFollowRedirects(true)}——攻击者用一个能通过 {@link HttpTargetGuard} 校验的
     * 公网域名返回 302 Location 指向内网地址，HttpURLConnection 会自动跟到内网，Guard 即被绕过。
     * 这里按连接实例覆写（不用 {@code HttpURLConnection.setFollowRedirects} 全局静态开关，避免污染
     * 同 JVM 其它 HTTP 客户端）。</p>
     */
    static class NoRedirectClientHttpRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }

    /**
     * 单次请求结果（贫血数据袋，序列化成 JSON 回给 LLM）：正常时带 {@code statusCode}/{@code body}，
     * 异常时 {@code error} 非空且 {@code statusCode} 为空。
     */
    @Getter
    @Builder
    public static final class HttpToolResult {
        private final String url;
        private final String method;
        private final Integer statusCode;
        private final String body;
        /** 3xx 重定向响应的 Location 头（工具不自动跟随，交由调用方决策下一跳）。 */
        private final String location;
        private final String error;
    }
}
