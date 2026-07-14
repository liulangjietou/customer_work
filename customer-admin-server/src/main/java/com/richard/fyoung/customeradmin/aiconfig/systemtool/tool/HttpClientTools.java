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

import java.util.Map;

/**
 * HTTP 请求工具（系统工具 {@code httpclient}）。给挂载了本工具的智能体提供 GET/DELETE/POST/POST-Form/PUT
 * 五种 HTTP 调用能力，供其在对话中访问外部/内网 HTTP 接口。
 *
 * <p>Bean 名精确等于 {@code tool_code}（"httpclient"），运行时
 * {@code AdminAgentInstanceFactory#buildSystemTools} 按此名从容器取实例注册进 Toolkit。</p>
 *
 * <p>安全说明：TLS 走 JDK 默认信任链校验（不做 trust-all / 不关主机名校验）；但本工具允许智能体请求
 * 任意可达 URL（含内网地址），是一个 SSRF 风险面，是否收紧到 URL 白名单后续按需迭代。</p>
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

    public HttpClientTools() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "50");
        this.restTemplate = new RestTemplate(factory);
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
        HttpResponse response;
        try {
            HttpEntity<String> entity = body != null ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, method, entity, String.class);
            response = HttpResponse.builder()
                .url(url).method(method.name()).statusCode(resp.getStatusCode().value())
                .body(resp.getBody()).build();
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
