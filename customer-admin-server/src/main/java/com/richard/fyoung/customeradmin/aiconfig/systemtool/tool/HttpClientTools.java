package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.devtool.HttpClientDevToolOps;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * HTTP 请求工具（系统工具 {@code httpclient}）。给挂载了本工具的智能体提供 GET/DELETE/POST/POST-Form/PUT
 * 五种 HTTP 调用能力，供其在对话中访问外部/内网 HTTP 接口。
 *
 * <p>Bean 名精确等于 {@code tool_code}（"httpclient"），运行时
 * {@code AdminAgentInstanceFactory#buildSystemTools} 按此名从容器取实例注册进 Toolkit——本类必须保留
 * {@code @Component("httpclient")} 与这几个 {@code @Tool} 方法（工具 Schema 由注解生成，不能搬走）。</p>
 *
 * <p><b>执行核心不在本类</b>：请求组装、禁重定向、SSRF 校验、结果序列化都在 starter 的
 * {@link HttpClientDevToolOps}。本类只做三件事：暴露 {@code @Tool} Schema、把阻塞调用放到
 * boundedElastic 线程池、把 starter 的 {@link HttpTargetForbiddenException} 转成 {@link BizException}。</p>
 *
 * <p>安全说明：TLS 走 JDK 默认信任链校验；SSRF 面在 {@link SystemToolHttpGuard} 承载的策略上收口
 * （默认拒内网/环回、放公网，配置 {@code admin.system-tool.http.allowed-hosts} 后仅放行白名单内 host），
 * 由执行核心在每次请求前调用；配套禁止自动跟随重定向，3xx 作为终态响应返回。</p>
 * @author owlzhangfq@gmail.com
 */
@Component("httpclient")
public class HttpClientTools {

    private final HttpClientDevToolOps httpOps;

    public HttpClientTools(SystemToolHttpGuard httpGuard) {
        this.httpOps = new HttpClientDevToolOps(httpGuard.targetGuard());
    }

    @Tool(name = "http_get", description = "get请求http地址")
    public Mono<String> getRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers) {
        return async(() -> httpOps.get(url, headers));
    }

    @Tool(name = "http_delete", description = "delete请求http地址")
    public Mono<String> deleteRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers) {
        return async(() -> httpOps.delete(url, headers));
    }

    @Tool(name = "http_post_form", description = "post请求http地址(form表单)")
    public Mono<String> postFormRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers,
            @ToolParam(name = "params", required = true, description = "form参数") Map<String, Object> forms) {
        return async(() -> httpOps.postForm(url, headers, forms));
    }

    @Tool(name = "http_post", description = "post请求http地址")
    public Mono<String> postRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers,
            @ToolParam(name = "body", description = "请求body") String body) {
        return async(() -> httpOps.post(url, headers, body));
    }

    @Tool(name = "http_put", description = "put请求http地址")
    public Mono<String> putRequest(
            @ToolParam(name = "url", description = "http地址") String url,
            @ToolParam(name = "header", required = false, description = "请求头") Map<String, String> headers,
            @ToolParam(name = "body", description = "请求body") String body) {
        return async(() -> httpOps.put(url, headers, body));
    }

    /**
     * 阻塞的 HTTP 调用放到 boundedElastic 线程池执行，不占用 reactor 事件循环线程；
     * 同时把 starter 的安全拦截异常转译成业务异常（异常转换单一收口）。
     */
    private Mono<String> async(Callable<String> task) {
        return Mono.fromCallable(() -> {
            try {
                return task.call();
            } catch (HttpTargetForbiddenException e) {
                throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
