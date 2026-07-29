package com.richard.fyoung.customeradmin.a2a;

import io.a2a.spec.AgentCard;
import io.a2a.spec.InternalError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A2A 协议端点：Agent Card 发现 + JSON-RPC 调用。
 *
 * <p>路径固定成协议规定的 {@code /.well-known/agent-card.json}——A2A 客户端就是靠这个约定路径
 * 自动发现服务端能力的，改成别的名字等于要求每个客户端手工配置，失去互操作的意义。</p>
 *
 * <p><b>这两个端点不走 Sa-Token 的权限拦截</b>：调用方是外部系统而不是后台登录用户，会话式鉴权
 * 对它们不适用。这一点不需要额外配置——{@code SaTokenConfig} 的拦截器只挂 {@code /api/**}，
 * 两个路径都在其外。</p>
 *
 * <p><b>但 Sa-Token 还有一道独立的请求路径防火墙</b>（{@code SaPathCheckFilter} →
 * {@code SaStrategy.checkRequestPath}），它作用于<b>所有</b>请求且无差别拒绝含 {@code "/."} 的路径，
 * 正好把协议规定的 {@code /.well-known/...} 挡在门外（表现为 "非法请求：..."）。放行逻辑见
 * {@link AdminA2aServerConfig#allowAgentCardPathThroughFirewall()}——权限拦截器与防火墙是两回事，
 * 只看前者会得出"无需任何配置"的错误结论。</p>
 *
 * <p><b>因此当前形态只适用于内网可信调用。</b>对外开放前必须在 Agent Card 的
 * {@code securitySchemes} 里声明鉴权方式并在本类落实校验，否则等于把智能体裸奔在网上——
 * 这也是 {@code admin.a2a.enabled} 默认关闭的原因之一。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@ConditionalOnProperty(prefix = "admin.a2a", name = "enabled", havingValue = "true")
public class A2aController {

    private static final Logger log = LoggerFactory.getLogger(A2aController.class);

    private static final String CODE_A2A_REQUEST_FAIL = "A2A-REQUEST-FAIL";

    private final AgentScopeA2aServer server;

    /**
     * 条件用 {@code @ConditionalOnProperty} 而不是 {@code @ConditionalOnBean(AgentScopeA2aServer.class)}。
     *
     * <p>后者在<b>组件扫描</b>的类上不可靠：它按 Bean 定义的注册顺序评估，而组件扫描到本类时
     * {@code AdminA2aServerConfig} 的 {@code @Bean} 方法往往尚未注册，条件判定为假 → Controller
     * 静默不注册 → 请求落到静态资源处理器，报 {@code NoResourceFoundException: No static resource
     * .well-known/agent-card.json}（实测踩过，且没有任何报错提示是条件没通过）。
     * Spring 文档也明确 {@code @ConditionalOnBean} 只应用于 {@code @Configuration} 的 {@code @Bean} 方法。
     * 改用与 {@link AdminA2aServerConfig} 完全相同的属性条件，两者要么一起在、要么一起不在。</p>
     */
    public A2aController(AgentScopeA2aServer server) {
        this.server = server;
        // 端点在本 Bean 创建时即随 MVC 就绪，此时通知框架完成注册中心上报等收尾动作
        // （当前未配注册中心，这一步是空转，但保留调用以免将来接 Nacos 时漏掉）
        server.postEndpointReady();
        log.info("[a2a] endpoints registered: {} , {}",
            AdminA2aServerConfig.AGENT_CARD_PATH, AdminA2aServerConfig.JSONRPC_PATH);
    }

    /** Agent Card 发现端点（A2A 协议约定路径）。 */
    @GetMapping(value = AdminA2aServerConfig.AGENT_CARD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentCard agentCard() {
        return server.getAgentCard();
    }

    /**
     * JSON-RPC 调用端点。
     *
     * <p>返回值有两种形态：流式请求（{@code message/stream}）得到 {@link Flux}，非流式得到单个响应对象。
     * 直接把框架返回的对象透出给 Spring MVC——它对两者都能正确序列化（Flux 走 SSE、单对象走 JSON），
     * 在这里手工分支反而会把框架已经处理好的协议细节做错。</p>
     */
    @PostMapping(value = AdminA2aServerConfig.JSONRPC_PATH, produces = {
        MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @SuppressWarnings("unchecked")
    public Object jsonRpc(@RequestBody String body, HttpServletRequest request) {
        try {
            // 框架的 getTransportWrapper 返回裸类型（其类上就标着 @SuppressWarnings("rawtypes")），
            // JSON-RPC 实现的实际签名是 TransportWrapper<String, Object>，按此收窄。
            // 注意它找不到时是【抛 IllegalArgumentException】而不是返回 null，别写 null 判断。
            TransportWrapper<String, Object> wrapper =
                server.getTransportWrapper(TransportProtocol.JSONRPC.asString());
            return wrapper.handleRequest(body, headersOf(request), Collections.emptyMap());
        } catch (Exception e) {
            // 必须在这里兜住：本模块的 @RestControllerAdvice 没有包限定，会把任何异常都转成
            // {"code":50000,"message":"系统繁忙"} 的业务响应——那既不是 A2A 协议格式（外部客户端
            // 无法解析），又把真实错误彻底吞掉（排查时只能看到"系统繁忙"）。
            // 这里改成协议规定的 JSON-RPC 错误响应，并把堆栈完整落日志。
            log.error("[a2a] json-rpc request failed, code={}", CODE_A2A_REQUEST_FAIL, e);
            return new JSONRPCErrorResponse(new InternalError(rootMessage(e)));
        }
    }

    /** 取最内层异常信息：框架把原始错误层层包装后，最外层往往只剩无信息的包装类名。 */
    private String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? cause.getClass().getSimpleName() + ": " + message
            : cause.getClass().getSimpleName();
    }

    /** 透传请求头：A2A 的鉴权与追踪信息都在头里，框架据此构建 ServerCallContext。 */
    private Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
            .forEach(name -> headers.put(name, request.getHeader(name)));
        return headers;
    }
}
