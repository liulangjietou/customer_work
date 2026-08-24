package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.mcp.McpClientFactory;
import com.richard.fyoung.customerwork.tool.mcp.McpSecurityPolicy;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.richard.fyoung.customerwork.infra.config.properties.McpProperties;

/**
 * MCP 接入装配器单测（特性「MCP 接入」）：开关与配置判定逻辑。
 * 不连接真实 MCP 服务，只验证装配决策。
 * @author owlzhangfq@gmail.com
 */
class McpToolkitConfigurerTest {

    @Test
    void isEnabled_shouldBeFalse_byDefault() {
        McpToolkitConfigurer configurer = new McpToolkitConfigurer(new CustomerWorkProperties(), new com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry());
        assertFalse(configurer.isEnabled(), "默认不启用 MCP");
    }

    @Test
    void isEnabled_shouldBeFalse_whenEnabledButNoServers() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMcp().setEnabled(true);
        assertFalse(new McpToolkitConfigurer(props, new com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry()).isEnabled(), "未配置服务时视为未启用");
    }

    @Test
    void isEnabled_shouldBeTrue_whenEnabledWithServers() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMcp().setEnabled(true);
        McpProperties.Server server = new McpProperties.Server();
        server.setName("inventory");
        server.setUrl("http://localhost:9000/sse");
        props.getMcp().getServers().add(server);

        assertTrue(new McpToolkitConfigurer(props, new com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry()).isEnabled());
    }

    @Test
    void configure_shouldBeNoOp_whenDisabled() {
        Toolkit toolkit = new Toolkit();
        int before = toolkit.getToolNames().size();

        new McpToolkitConfigurer(new CustomerWorkProperties(), new com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry()).configure(toolkit);

        assertEquals(before, toolkit.getToolNames().size(), "未启用时不应改动 toolkit");
    }

    /**
     * 回归用例（修复"yml 配置的 headers 未透传、鉴权型 MCP 服务 401"）：起本地 HTTP server
     * 捕获真实请求头。连接会因 server 回 500 而失败，configure 按设计吞掉单服务失败不阻断启动，
     * 本用例只断言 Authorization 头确实随连接请求发出。
     */
    @Test
    void configure_shouldSendConfiguredHeaders() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/mcp", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        httpServer.start();
        try {
            CustomerWorkProperties props = new CustomerWorkProperties();
            props.getMcp().setEnabled(true);
            McpProperties.Server server = new McpProperties.Server();
            server.setName("secured");
            server.setTransport("streamable-http");
            server.setUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp");
            server.setHeaders(Map.of("Authorization", "Bearer starter-token"));
            props.getMcp().getServers().add(server);

            McpSecurityPolicy testPolicy = new McpSecurityPolicy(List::of, List::of, List::of, List::of,
                host -> new InetAddress[]{InetAddress.getByAddress(host,
                    new byte[]{93, (byte) 184, (byte) 216, 34})});
            new McpToolkitConfigurer(props, new com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry(),
                new McpClientFactory(testPolicy)).configure(new Toolkit());

            assertEquals("Bearer starter-token", receivedAuth.get(), "配置的 Authorization 头应随请求发出");
        } finally {
            httpServer.stop(0);
        }
    }
}
