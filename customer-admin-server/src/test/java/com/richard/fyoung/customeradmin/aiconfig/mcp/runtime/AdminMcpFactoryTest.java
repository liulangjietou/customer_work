package com.richard.fyoung.customeradmin.aiconfig.mcp.runtime;

import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link AdminMcpFactory} 单测：三种 mcpType 都能正确解析出对应的传输方式（不发起真实连接——
 * MCP 协议握手需要真实服务端，留给联调阶段；这里只验证 config JSON -> McpClientBuilder 的
 * 解析分支没有写错）。真实不可达地址的 {@code testConnectivity} 应判定失败，不抛异常。
 * @author owlzhangfq@gmail.com
 */
class AdminMcpFactoryTest {

    private final AdminMcpFactory factory = new AdminMcpFactory();

    @Test
    void buildClientBuilder_shouldSucceed_forSseType() {
        McpClientBuilder builder = assertDoesNotThrow(() ->
            factory.buildClientBuilder("test", "sse", "{\"url\": \"https://mcp.example.com/sse\"}"));

        assertNotNull(builder);
    }

    @Test
    void buildClientBuilder_shouldSucceed_forHttpType() {
        McpClientBuilder builder = assertDoesNotThrow(() ->
            factory.buildClientBuilder("test", "http", "{\"url\": \"https://mcp.example.com/mcp\"}"));

        assertNotNull(builder);
    }

    @Test
    void buildClientBuilder_shouldSucceed_forStdioType() {
        McpClientBuilder builder = assertDoesNotThrow(() ->
            factory.buildClientBuilder("test", "stdio", "{\"command\": \"python\", \"args\": [\"-m\", \"mcp_server\"]}"));

        assertNotNull(builder);
    }

    @Test
    void testConnectivity_shouldFail_whenUrlUnreachable() {
        McpTestResult result = factory.testConnectivity("test", "sse", "{\"url\": \"http://127.0.0.1:1\"}");

        assertEquals(McpTestResult.STATUS_FAILED, result.testStatus());
        assertNotNull(result.message());
    }

    /**
     * Claude Desktop / Cursor 等主流客户端的标准配置格式外层带一层 {@code mcpServers} 包装——
     * 用户直接照抄 MCP 服务文档大概率带这层，必须能自动解开，否则内层 url 读不到会变成空字符串
     * 悄悄发往空地址（真实联调时踩过的坑）。
     */
    @Test
    void buildClientBuilder_shouldUnwrapMcpServersWrapper_forHttpType() {
        String wrapped = "{\"mcpServers\": {\"oa-server\": {\"url\": \"http://localhost:3002/mcp\"}}}";

        McpClientBuilder builder = assertDoesNotThrow(() ->
            factory.buildClientBuilder("test", "http", wrapped));

        assertNotNull(builder);
    }

    @Test
    void testConnectivity_shouldFail_notNotInitialized_whenMcpServersWrapperUrlUnreachable() {
        String wrapped = "{\"mcpServers\": {\"oa-server\": {\"url\": \"http://127.0.0.1:1/mcp\"}}}";

        McpTestResult result = factory.testConnectivity("test", "http", wrapped);

        assertEquals(McpTestResult.STATUS_FAILED, result.testStatus());
        assertNotNull(result.message());
    }
}
