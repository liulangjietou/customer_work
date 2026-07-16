package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.tool.dto.HttpResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

/**
 * HTTP 请求工具（系统工具 {@code httpclient}）。给挂载了本工具的智能体提供 GET/DELETE/POST/POST-Form/PUT
 * 五种 HTTP 调用能力，供其在对话中访问外部/内网 HTTP 接口。
 *
 * <p>Bean 名精确等于 {@code tool_code}（"httpclient"），运行时
 * {@code AdminAgentInstanceFactory#buildSystemTools} 按此名从容器取实例注册进 Toolkit。</p>
 *
 * <p>安全说明：TLS 走 JDK 默认信任链校验（不做 trust-all / 不关主机名校验）；SSRF 面在
 * {@link SystemToolHttpGuard} 收口——发起请求前对目标 URL 做白名单/内网判定（该链路唯一防御点），
 * 默认拒内网/环回、放公网，配置白名单后仅放行白名单内 host（见 {@code admin.system-tool.http.allowed-hosts}）。
 * 配套地，本工具**禁止自动跟随重定向**（见 {@link NoRedirectClientHttpRequestFactory}）：3xx 作为终态
 * 响应返回（带 statusCode + location），Agent 若要跟进下一跳需再发一次工具调用，自然再过一遍 Guard——
 * 否则"合规公网域名 302 指向内网地址"即可绕过校验。</p>
 * @author owlzhangfq@gmail.com
 */
@Component("httpclient")
public class HttpClientTools {

    private static final Logger log = LoggerFactory.getLogger(HttpClientTools.class);

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String ERROR_CODE_HTTP_FAIL = "SYSTOOL-HTTP-FAIL";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SystemToolHttpGuard httpGuard;

    public HttpClientTools(SystemToolHttpGuard httpGuard) {
        this.httpGuard = httpGuard;
        SimpleClientHttpRequestFactory factory = new NoRedirectClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "50");
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 所有方法统一禁止自动跟随重定向的请求工厂（SSRF 收口的配套约束，不要移除）。
     *
     * <p>Spring 的 {@link SimpleClientHttpRequestFactory#prepareConnection} 默认对 GET 开
     * {@code setInstanceFollowRedirects(true)}——攻击者用一个能通过 {@link SystemToolHttpGuard}
     * 校验的公网域名返回 302 Location 指向内网地址，HttpURLConnection 会自动跟到内网，Guard 即被绕过。
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

    @Tool(name = "http_get", description = "get请求http地址")
    public Mono<String> getRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers) {
        return async(() -> execute(url, HttpMethod.GET, buildHeaders(headers), null));
    }

    @Tool(name = "http_delete", description = "delete请求http地址")
    public Mono<String> deleteRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers) {
        return async(() -> execute(url, HttpMethod.DELETE, buildHeaders(headers), null));
    }

    @Tool(name = "http_post_form", description = "post请求http地址(form表单)")
    public Mono<String> postFormRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers,
            @ToolParam(name = "params", required = true, description = "form参数") Map<String, Object> forms) {
        return async(() -> {
            HttpHeaders h = buildHeaders(headers);
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            StringBuilder sb = new StringBuilder();
            if (forms != null) {
                for (Map.Entry<String, Object> e : forms.entrySet()) {
                    if (sb.length() > 0) {
                        sb.append("&");
                    }
                    sb.append(e.getKey()).append("=").append(e.getValue());
                }
            }
            return execute(url, HttpMethod.POST, h, sb.toString());
        });
    }

    @Tool(name = "http_post", description = "post请求http地址")
    public Mono<String> postRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers,
            @ToolParam(name = "body", description = "请求body") String body) {
        return async(() -> {
            HttpHeaders h = buildHeaders(headers);
            h.setContentType(MediaType.APPLICATION_JSON);
            return execute(url, HttpMethod.POST, h, body);
        });
    }

    @Tool(name = "http_put", description = "put请求http地址")
    public Mono<String> putRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers,
            @ToolParam(name = "body", description = "请求body") String body) {
        return async(() -> {
            HttpHeaders h = buildHeaders(headers);
            h.setContentType(MediaType.APPLICATION_JSON);
            return execute(url, HttpMethod.PUT, h, body);
        });
    }

    /** 阻塞的 RestTemplate 调用放到 boundedElastic 线程池执行，不占用 reactor 事件循环线程。 */
    private Mono<String> async(java.util.concurrent.Callable<String> task) {
        return Mono.fromCallable(task).subscribeOn(Schedulers.boundedElastic());
    }

    /** 发起一次请求并把结果序列化成 JSON 字符串回传给 LLM；任何异常都收敛成 error 字段而非抛出。 */
    private String execute(String url, HttpMethod method, HttpHeaders headers, String body) {
        // SSRF 收口：发起请求前统一校验目标地址（唯一防御点），命中安全策略 fast fail。
        httpGuard.checkAllowed(url);
        HttpResponse response;
        try {
            HttpEntity<String> entity = body != null ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, method, entity, String.class);
            // 3xx 是终态结果（不自动跟随），带回 Location 让调用方决策下一跳
            URI redirectLocation = resp.getHeaders().getLocation();
            response = HttpResponse.builder()
                .url(url).method(method.name()).statusCode(resp.getStatusCode().value())
                .body(resp.getBody())
                .location(redirectLocation == null ? null : redirectLocation.toString())
                .build();
        } catch (Exception e) {
            log.error("http tool request failed, code={}, url={}, method={}", ERROR_CODE_HTTP_FAIL, url, method, e);
            response = HttpResponse.builder()
                .url(url).method(method.name()).error(e.getMessage()).build();
        }
        return toJson(response);
    }

    private String toJson(HttpResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("http tool serialize response failed, code={}, url={}", ERROR_CODE_HTTP_FAIL, response.getUrl(), e);
            return "{\"url\":\"" + response.getUrl() + "\",\"error\":\"serialize response failed\"}";
        }
    }

    private HttpHeaders buildHeaders(Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        if (headers != null) {
            headers.forEach(h::add);
        }
        return h;
    }
}
