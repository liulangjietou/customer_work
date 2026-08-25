package com.richard.fyoung.customerwork.tool.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Streamable HTTP 兼容层：服务端可以用 405 拒绝可选的 GET 事件流，客户端继续使用 POST 请求/响应模式。
 *
 * <p>MCP Java SDK 0.17.0 会先把 GET 的 405 JSON 正文交给 SSE 解析器，因而在识别 405 之前抛出
 * {@code Invalid SSE response}。该异常只代表服务端不提供可选的独立事件流，不代表 POST 端点不可用。
 * 本类仅在 Streamable HTTP 传输外层过滤这一种已知信号，其他传输异常仍原样交给 MCP 生命周期处理器。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class StreamableHttpCompatibilityTransport implements McpClientTransport {

    private static final String OPTIONAL_GET_REJECTED_PREFIX =
        "Invalid SSE response. Status code: 405";

    private final McpClientTransport delegate;

    StreamableHttpCompatibilityTransport(McpClientTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(handler);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        delegate.setExceptionHandler(error -> {
            if (!isOptionalGetRejected(error)) {
                handler.accept(error);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return delegate.sendMessage(message);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return delegate.unmarshalFrom(data, typeRef);
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    static boolean isOptionalGetRejected(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof McpTransportException
                    && cause.getMessage() != null
                    && cause.getMessage().startsWith(OPTIONAL_GET_REJECTED_PREFIX)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
