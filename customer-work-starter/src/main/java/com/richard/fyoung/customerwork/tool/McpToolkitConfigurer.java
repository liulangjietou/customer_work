package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.mcp.McpClientFactory;
import com.richard.fyoung.customerwork.tool.mcp.McpServerSpec;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import com.richard.fyoung.customerwork.infra.config.properties.McpProperties;

/**
 * MCP 接入装配器（对应特性「MCP 接入」）。
 *
 * <p>把存量 HTTP 业务系统通过 MCP 协议零改造接成 Agent 可调用的工具：按配置的服务列表
 * 构建 {@link McpClientWrapper} 并注册到 {@link Toolkit}。默认关闭，配置后即生效。</p>
 *
 * <p>注册在应用启动 / Agent 装配期同步完成（{@code block}），属一次性初始化开销，可接受。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class McpToolkitConfigurer {

    private static final Logger log = LoggerFactory.getLogger(McpToolkitConfigurer.class);

    /** yml 里表示 streamable http 传输的取值（其余取值一律按 sse 处理）。 */
    private static final String TRANSPORT_STREAMABLE_HTTP = "streamable-http";
    /** 启动期注册的连接超时。 */
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(30);

    private final CustomerWorkProperties properties;
    /** 工具归类登记表：MCP 工具在此登记名称，供分段耗时统计按类归段。 */
    private final ToolKindRegistry toolKindRegistry;
    /** 「规格 -> 客户端」的构建核心，与后台管理侧（库里配置的 MCP）共用同一份实现。 */
    private final McpClientFactory mcpClientFactory = new McpClientFactory();

    public McpToolkitConfigurer(CustomerWorkProperties properties, ToolKindRegistry toolKindRegistry) {
        this.properties = properties;
        this.toolKindRegistry = toolKindRegistry;
    }

    /** 是否启用 MCP（且配置了至少一个服务）。 */
    public boolean isEnabled() {
        McpProperties mcp = properties.getMcp();
        return mcp.isEnabled() && mcp.getServers() != null && !mcp.getServers().isEmpty();
    }

    /** 把所有已配置的 MCP 服务注册到给定 toolkit。未启用时为 no-op。 */
    public void configure(Toolkit toolkit) {
        if (!isEnabled()) {
            return;
        }
        for (McpProperties.Server server : properties.getMcp().getServers()) {
            try {
                McpClientWrapper wrapper = buildClient(server).block();
                if (wrapper != null) {
                    // 快照注册前工具名，注册后取增量即为本 MCP 服务贡献的工具，登记为 MCP 类别
                    // （直接用 toolkit 实际工具名做键，与 onActing 的 ToolUseBlock.getName() 一致，最可靠）
                    Set<String> before = new HashSet<>(toolkit.getToolNames());
                    toolkit.registerMcpClient(wrapper).block();
                    Set<String> added = new HashSet<>(toolkit.getToolNames());
                    added.removeAll(before);
                    toolKindRegistry.registerMcpTools(added);
                    log.info("[MCP] 已接入服务 name={} url={} tools={}", server.getName(), server.getUrl(), added);
                }
            } catch (Exception e) {
                // 单个 MCP 服务不可用不应阻断整个应用启动
                log.error("[MCP] 接入服务失败 name={} url={}: {}",
                    server.getName(), server.getUrl(), e.getMessage());
            }
        }
    }

    /**
     * yml 配置项 -&gt; 中立的 {@link McpServerSpec} -&gt; 客户端。传输取值的映射刻意留在这里：
     * yml 用的是 {@code streamable-http}（历史配置项文案），与库配置侧的 {@code http} 不是同一套字面量，
     * 归一动作属于本配置源自己的语义，不下沉到共用构建核心里去。
     *
     * <p>需要鉴权的远程 MCP 服务，配置的附加请求头（如 Authorization）由构建核心统一透传给握手与后续调用。</p>
     */
    private reactor.core.publisher.Mono<McpClientWrapper> buildClient(
            McpProperties.Server server) {
        String type = TRANSPORT_STREAMABLE_HTTP.equalsIgnoreCase(server.getTransport())
            ? McpServerSpec.TYPE_HTTP
            : McpServerSpec.TYPE_SSE;
        return mcpClientFactory
            .buildClientBuilder(McpServerSpec.remote(server.getName(), type, server.getUrl(), server.getHeaders()))
            .timeout(CLIENT_TIMEOUT)
            .buildAsync();
    }
}
