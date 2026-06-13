package com.example.customerwork.tool;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * MCP 接入装配器（对应特性「MCP 接入」）。
 *
 * <p>把存量 HTTP 业务系统通过 MCP 协议零改造接成 Agent 可调用的工具：按配置的服务列表
 * 构建 {@link McpClientWrapper} 并注册到 {@link Toolkit}。默认关闭，配置后即生效。</p>
 *
 * <p>注册在应用启动 / Agent 装配期同步完成（{@code block}），属一次性初始化开销，可接受。</p>
 */
@Component
public class McpToolkitConfigurer {

    private static final Logger log = LoggerFactory.getLogger(McpToolkitConfigurer.class);

    private final CustomerWorkProperties properties;

    public McpToolkitConfigurer(CustomerWorkProperties properties) {
        this.properties = properties;
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
                    toolkit.registerMcpClient(wrapper).block();
                    log.info("[MCP] 已接入服务 name={} url={}", server.getName(), server.getUrl());
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
        return builder.buildAsync();
    }
}
