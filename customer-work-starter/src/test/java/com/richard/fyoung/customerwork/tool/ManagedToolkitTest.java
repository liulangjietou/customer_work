package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedToolkitTest {

    @Test
    void close_shouldReleaseRegisteredClientsExactlyOnce() {
        ManagedToolkit toolkit = new ManagedToolkit();
        TestMcpClient client = new TestMcpClient("inventory", Mono.empty());

        toolkit.registerMcpClient(client).block();

        assertEquals(Set.of("inventory"), toolkit.registeredMcpClientNames());
        assertSame(toolkit, toolkit.copy(), "Agent Builder 复制时必须保留同一个资源所有者");

        toolkit.close();
        toolkit.close();

        assertEquals(1, client.closeCount.get(), "容器销毁与缓存淘汰重复触发时也只能关闭一次");
        assertTrue(toolkit.registeredMcpClientNames().isEmpty());
    }

    @Test
    void registrationCompletingAfterClose_shouldReleaseClientOutsideCloseSnapshot() throws Exception {
        Sinks.One<Void> initialization = Sinks.one();
        ManagedToolkit toolkit = new ManagedToolkit();
        TestMcpClient client = new TestMcpClient("slow", initialization.asMono());

        CompletableFuture<Void> registration = toolkit.registerMcpClient(client).toFuture();
        toolkit.close();
        initialization.tryEmitEmpty();

        registration.get(1, TimeUnit.SECONDS);
        assertEquals(1, client.closeCount.get(), "并发完成注册的客户端不能逃逸关闭快照");
        assertTrue(toolkit.registeredMcpClientNames().isEmpty());
    }

    @Test
    void registerAfterClose_shouldFailBeforeOpeningClient() {
        ManagedToolkit toolkit = new ManagedToolkit();
        TestMcpClient client = new TestMcpClient("late", Mono.empty());
        toolkit.close();

        assertThrows(IllegalStateException.class, () -> toolkit.registerMcpClient(client).block());
        assertEquals(0, client.initializeCount.get(), "已关闭 Toolkit 不应再发起 MCP 握手");
        assertEquals(0, client.closeCount.get(), "所有权未移交时仍由调用方负责关闭");
    }

    private static final class TestMcpClient extends McpClientWrapper {

        private final Mono<Void> initialization;
        private final AtomicInteger initializeCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

        private TestMcpClient(String name, Mono<Void> initialization) {
            super(name);
            this.initialization = initialization;
        }

        @Override
        public Mono<Void> initialize() {
            initializeCount.incrementAndGet();
            return initialization;
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(List.of());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
            return Mono.empty();
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments,
                                                       Map<String, Object> meta) {
            return Mono.empty();
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
