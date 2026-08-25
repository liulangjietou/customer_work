package com.richard.fyoung.customerwork.tool.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class StreamableHttpCompatibilityTransportTest {

    @Test
    void exceptionHandler_shouldIgnoreOnlyOptionalGet405() {
        FakeTransport delegate = new FakeTransport();
        StreamableHttpCompatibilityTransport transport = new StreamableHttpCompatibilityTransport(delegate);
        AtomicReference<Throwable> forwarded = new AtomicReference<>();
        transport.setExceptionHandler(forwarded::set);

        delegate.emit(new McpTransportException(
            "Invalid SSE response. Status code: 405 Line: {\"error\":\"GET not supported, use POST\"}"));

        assertNull(forwarded.get());

        McpTransportException serverFailure = new McpTransportException(
            "Invalid SSE response. Status code: 500 Line: internal error");
        delegate.emit(serverFailure);

        assertSame(serverFailure, forwarded.get());
    }

    private static final class FakeTransport implements McpClientTransport {

        private Consumer<Throwable> exceptionHandler;

        @Override
        public Mono<Void> connect(
                Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            return Mono.empty();
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> handler) {
            this.exceptionHandler = handler;
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return null;
        }

        @Override
        public List<String> protocolVersions() {
            return List.of("2025-06-18");
        }

        private void emit(Throwable error) {
            exceptionHandler.accept(error);
        }
    }
}
