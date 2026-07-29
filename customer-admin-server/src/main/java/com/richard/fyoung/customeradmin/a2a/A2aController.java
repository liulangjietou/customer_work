package com.richard.fyoung.customeradmin.a2a;

import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * <p><b>这两个端点不走 Sa-Token 鉴权</b>：调用方是外部系统而不是后台登录用户，会话式鉴权对它们
 * 不适用。无需额外配置放行——{@code SaTokenConfig} 的拦截器只挂 {@code /api/**}，而这两个路径
 * 一个是协议规定的 {@code /.well-known/*}、一个在 {@code /a2a/*}，本就在拦截范围之外。</p>
 *
 * <p><b>因此当前形态只适用于内网可信调用。</b>对外开放前必须在 Agent Card 的
 * {@code securitySchemes} 里声明鉴权方式并在本类落实校验，否则等于把智能体裸奔在网上——
 * 这也是 {@code admin.a2a.enabled} 默认关闭的原因之一。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@ConditionalOnBean(AgentScopeA2aServer.class)
public class A2aController {

    private static final Logger log = LoggerFactory.getLogger(A2aController.class);

    private static final String CODE_A2A_TRANSPORT_MISSING = "A2A-TRANSPORT-MISSING";

    private final AgentScopeA2aServer server;

    public A2aController(AgentScopeA2aServer server) {
        this.server = server;
        // 端点在本 Bean 创建时即随 MVC 就绪，此时通知框架完成注册中心上报等收尾动作
        // （当前未配注册中心，这一步是空转，但保留调用以免将来接 Nacos 时漏掉）
        server.postEndpointReady();
    }

    /** Agent Card 发现端点（A2A 协议约定路径）。 */
    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
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
        // 框架的 getTransportWrapper 返回裸类型（其类上就标着 @SuppressWarnings("rawtypes")），
        // JSON-RPC 实现的实际签名是 TransportWrapper<String, Object>，按此收窄
        TransportWrapper<String, Object> wrapper =
            server.getTransportWrapper(TransportProtocol.JSONRPC.asString());
        if (wrapper == null) {
            log.error("[a2a] json-rpc transport wrapper missing, code={}", CODE_A2A_TRANSPORT_MISSING);
            throw new IllegalStateException("A2A JSON-RPC transport is not available");
        }
        return wrapper.handleRequest(body, headersOf(request), Collections.emptyMap());
    }

    /** 透传请求头：A2A 的鉴权与追踪信息都在头里，框架据此构建 ServerCallContext。 */
    private Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
            .forEach(name -> headers.put(name, request.getHeader(name)));
        return headers;
    }
}
