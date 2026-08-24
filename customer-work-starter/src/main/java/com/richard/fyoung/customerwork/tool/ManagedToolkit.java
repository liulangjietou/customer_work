package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对 MCP 客户端拥有明确生命周期的 Toolkit。
 *
 * <p>AgentScope 的 {@link Toolkit} 会在注册 MCP 时持有客户端，却没有整体关闭入口；仅丢弃 Agent
 * 引用不会关闭 SSE/HTTP 连接与客户端线程。本类记录成功注册的客户端，并在 {@link #close()} 时通过
 * {@link Toolkit#removeMcpClient(String)} 同时移除工具和关闭底层客户端。</p>
 *
 * <p>注册与关闭的竞态也收敛在这里：关闭开始后才完成注册的客户端会立即自清理，不会落到关闭快照之外。
 * {@code close()} 幂等，适合缓存淘汰、显式会话结束与 Spring 容器销毁共同调用。</p>
 * @author owlzhangfq@gmail.com
 */
public class ManagedToolkit extends Toolkit implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ManagedToolkit.class);
    private static final String CODE_MCP_CLOSE_FAIL = "MCP-CLIENT-CLOSE-FAIL";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final Object lifecycleMonitor = new Object();
    private final Set<String> mcpClientNames = new LinkedHashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public Mono<Void> registerMcpClient(McpClientWrapper wrapper) {
        if (wrapper == null) {
            return Mono.error(new IllegalArgumentException("MCP client wrapper cannot be null"));
        }
        if (closed.get()) {
            return Mono.error(new IllegalStateException("Toolkit is already closed"));
        }
        return super.registerMcpClient(wrapper).then(Mono.defer(() -> {
            synchronized (lifecycleMonitor) {
                if (!closed.get()) {
                    mcpClientNames.add(wrapper.getName());
                    return Mono.empty();
                }
            }
            // 注册完成与关闭并发：关闭快照可能已经生成，由本次注册自行回收。
            return super.removeMcpClient(wrapper.getName());
        }));
    }

    /**
     * 返回自身以保留生命周期所有权。
     *
     * <p>本项目的 Toolkit 都按 Agent 新建且构建后不再共享；若让 Builder 复制成基类 Toolkit，
     * MCP 客户端所有权会从本实例脱离，缓存淘汰时便无法关闭。</p>
     */
    @Override
    public Toolkit copy() {
        return this;
    }

    @Override
    public void close() {
        List<String> clients;
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            clients = new ArrayList<>(mcpClientNames);
            mcpClientNames.clear();
        }
        Collections.reverse(clients);
        for (String clientName : clients) {
            try {
                super.removeMcpClient(clientName).block(CLOSE_TIMEOUT);
            } catch (Exception e) {
                log.error("MCP client close failed, code={}, clientName={}",
                    CODE_MCP_CLOSE_FAIL, clientName, e);
            }
        }
    }

    /** 供确定性生命周期单测观察，不暴露可变集合。 */
    Set<String> registeredMcpClientNames() {
        synchronized (lifecycleMonitor) {
            return Set.copyOf(mcpClientNames);
        }
    }
}
