package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.calllog.ToolKindRegistry;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

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

    private final CustomerWorkProperties properties;
    /** 工具归类登记表：MCP 工具在此登记名称，供分段耗时统计按类归段。 */
    private final ToolKindRegistry toolKindRegistry;

    public McpToolkitConfigurer(CustomerWorkProperties properties, ToolKindRegistry toolKindRegistry) {
        this.properties = properties;
        this.toolKindRegistry = toolKindRegistry;
    }

    /** 是否启用 MCP（且配置了至少一个服务）。 */
    public boolean isEnabled() {
        CustomerWorkProperties.Mcp mcp = properties.getMcp();
        return mcp.isEnabled() && mcp.getServers() != null && !mcp.getServers().isEmpty();
    }

    /** 把所有已配置的 MCP 服务注册到给定 toolkit。未启用时为 no-op。 */
    public void configure(Toolkit toolkit) {
        if (!isEnabled()) {
            return;
        }
        for (CustomerWorkProperties.Mcp.Server server : properties.getMcp().getServers()) {
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

    private reactor.core.publisher.Mono<McpClientWrapper> buildClient(
            CustomerWorkProperties.Mcp.Server server) {
        McpClientBuilder builder = McpClientBuilder.create(server.getName())
            .timeout(Duration.ofSeconds(30));
        if ("streamable-http".equalsIgnoreCase(server.getTransport())) {
            builder.streamableHttpTransport(server.getUrl());
        } else {
            builder.sseTransport(server.getUrl());
        }
        // 需要鉴权的远程 MCP 服务：把配置的附加请求头（如 Authorization）透传给握手与后续调用
        if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
            builder.headers(server.getHeaders());
        }
        return builder.buildAsync();
    }
}
