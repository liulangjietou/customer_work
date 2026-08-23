package com.richard.fyoung.customerwork.tool.mcp;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpClientFactory} 单测：三种传输类型都能正确解析出对应的传输方式（不发起真实连接——
 * MCP 协议握手需要真实服务端，留给联调阶段；这里只验证 config JSON -&gt; 规格 -&gt; McpClientBuilder 的
 * 解析分支没有写错）。真实不可达地址的 {@code testConnectivity} 应判定失败，不抛异常。
 * @author owlzhangfq@gmail.com
 */
class McpClientFactoryTest {

    private final McpClientFactory factory = new McpClientFactory();

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

    /** 解析层单独可验：stdio 配置的 command/args 应原样落进规格。 */
    @Test
    void parseSpec_shouldExtractCommandAndArgs_forStdioType() throws Exception {
        McpServerSpec spec = factory.parseSpec("test", "stdio",
            "{\"command\": \"python\", \"args\": [\"-m\", \"mcp_server\"]}");

        assertEquals(McpServerSpec.TYPE_STDIO, spec.type());
        assertEquals("python", spec.command());
        assertEquals(List.of("-m", "mcp_server"), spec.args());
    }

    /** 未知/空传输类型按 sse 兜底（历史行为，多数远程 MCP 服务默认走 sse）。 */
    @Test
    void parseSpec_shouldFallbackToSse_forUnknownType() throws Exception {
        McpServerSpec spec = factory.parseSpec("test", null, "{\"url\": \"https://mcp.example.com/sse\"}");

        assertEquals(McpServerSpec.TYPE_SSE, spec.type());
        assertEquals("https://mcp.example.com/sse", spec.url());
        assertNull(spec.headers(), "未配置 headers 时不应造出空 Map");
    }

    @Test
    void parseSpec_shouldRejectCredentialsOrMetadataInRemoteUrl() {
        assertThrows(Exception.class, () -> factory.parseSpec("test", "http",
            "{\"url\":\"https://user:secret@mcp.example.com/mcp\"}"));
        assertThrows(Exception.class, () -> factory.parseSpec("test", "http",
            "{\"url\":\"https://mcp.example.com/mcp?api_key=secret\"}"));
        assertThrows(Exception.class, () -> factory.parseSpec("test", "sse",
            "{\"url\":\"https://mcp.example.com/sse#token\"}"));
    }

    @Test
    void testConnectivity_shouldFail_whenUrlUnreachable() {
        McpConnectivityResult result = factory.testConnectivity("test", "sse", "{\"url\": \"http://127.0.0.1:1\"}");

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    /**
     * Claude Desktop / Cursor 等主流客户端的标准配置格式外层带一层 {@code mcpServers} 包装——
     * 用户直接照抄 MCP 服务文档大概率带这层，必须能自动解开，否则内层 url 读不到会变成空字符串
     * 悄悄发往空地址（真实联调时踩过的坑）。
     */
    @Test
    void parseSpec_shouldUnwrapMcpServersWrapper_forHttpType() throws Exception {
        String wrapped = "{\"mcpServers\": {\"oa-server\": {\"url\": \"http://localhost:3002/mcp\"}}}";

        McpServerSpec spec = factory.parseSpec("test", "http", wrapped);

        assertEquals("http://localhost:3002/mcp", spec.url());
        assertNotNull(assertDoesNotThrow(() -> factory.buildClientBuilder(spec)));
    }

    @Test
    void testConnectivity_shouldFail_notNotInitialized_whenMcpServersWrapperUrlUnreachable() {
        String wrapped = "{\"mcpServers\": {\"oa-server\": {\"url\": \"http://127.0.0.1:1/mcp\"}}}";

        McpConnectivityResult result = factory.testConnectivity("test", "http", wrapped);

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void buildClientBuilder_shouldSucceed_withHeaders_forSseType() {
        McpClientBuilder builder = assertDoesNotThrow(() -> factory.buildClientBuilder("test", "sse",
            "{\"url\": \"https://mcp.example.com/sse\", \"headers\": {\"Authorization\": \"Bearer t\"}}"));

        assertNotNull(builder);
    }

    /**
     * 回归用例（修复"config 里配置的 headers 被丢弃、鉴权型 MCP 服务 401"）：起本地 HTTP server
     * 捕获真实请求头，断言 Authorization 确实随连接请求发出。不模拟完整 MCP 握手（server 直接
     * 回 500，连通性结果必然失败，与本用例无关），只验证 headers 透传这一环。
     */
    @Test
    void testConnectivity_shouldSendConfiguredHeaders() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String config = "{\"url\": \"http://127.0.0.1:" + server.getAddress().getPort()
                + "/mcp\", \"headers\": {\"Authorization\": \"Bearer test-token\"}}";

            factory.testConnectivity("test", "http", config);

            assertEquals("Bearer test-token", receivedAuth.get(), "配置的 Authorization 头应随请求发出");
        } finally {
            server.stop(0);
        }
    }

    /** 用户实际场景：Claude Desktop 标准格式（mcpServers 包装）里带 headers，解包后同样要透传。 */
    @Test
    void testConnectivity_shouldSendConfiguredHeaders_withMcpServersWrapper() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String config = "{\"mcpServers\": {\"nebula-sql\": {\"type\": \"http\", \"url\": \"http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/mcp\", \"headers\": {\"Authorization\": \"Bearer wrapped-token\"}}}}";

            factory.testConnectivity("test", "http", config);

            assertEquals("Bearer wrapped-token", receivedAuth.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * listTools/callTool 跟 testConnectivity 不一样，不在方法内部兜底成失败结果，
     * 而是直接把连接异常往外抛——交给调用方（如后台 McpService）统一包成自己的业务异常，
     * 这里只验证"连不上会抛异常"这个边界，不吞掉。
     */
    @Test
    void listTools_shouldThrow_whenUrlUnreachable() {
        assertThrows(Exception.class, () ->
            factory.listTools("test", "sse", "{\"url\": \"http://127.0.0.1:1\"}"));
    }

    @Test
    void callTool_shouldThrow_whenUrlUnreachable() {
        assertThrows(Exception.class, () ->
            factory.callTool("test", "sse", "{\"url\": \"http://127.0.0.1:1\"}", "any-tool", Map.of()));
    }

    /**
     * 回归用例：修复"调试面板返回图片时乱码/空白"的 bug 前，{@code toCallResult} 只提取
     * {@code TextContent}，{@code ImageContent} 被静默丢弃。这里验证混合内容（文本+图片）时
     * 两类内容块各自正确提取，互不覆盖。
     */
    @Test
    void toCallResult_shouldExtractBothTextAndImageContent() {
        McpSchema.TextContent text = new McpSchema.TextContent("已生成图表");
        McpSchema.ImageContent image = new McpSchema.ImageContent(null, "aGVsbG8=", "image/png");
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(text, image), false);

        McpToolCallResult callResult = factory.toCallResult(result);

        assertTrue(callResult.success());
        assertEquals("已生成图表", callResult.output());
        assertEquals(1, callResult.images().size());
        assertEquals("image/png", callResult.images().get(0).mimeType());
        assertEquals("aGVsbG8=", callResult.images().get(0).data());
        assertFalse(callResult.outputLooksBinary());
    }

    /** 纯图片结果（无文本块）：output 应为空字符串而不是 null，images 不应为空列表。 */
    @Test
    void toCallResult_shouldReturnEmptyOutput_whenOnlyImageContent() {
        McpSchema.ImageContent image = new McpSchema.ImageContent(null, "aGVsbG8=", "image/jpeg");
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(image), false);

        McpToolCallResult callResult = factory.toCallResult(result);

        assertTrue(callResult.success());
        assertEquals("", callResult.output());
        assertFalse(callResult.images().isEmpty());
        assertFalse(callResult.outputLooksBinary());
    }

    /**
     * 回归用例：真实场景复现——通用文件系统 MCP 工具（如 {@code read_file}）不区分文件是否为二进制，
     * 把图片文件按文本读出后通过 TextContent 返回，二进制字节已在服务端损毁、无法还原，但应识别出
     * 这个模式并标记 {@code outputLooksBinary=true}，而不是让调用方原样展示一坨乱码。
     */
    @Test
    void toCallResult_shouldFlagOutputLooksBinary_whenTextContentIsCorruptedBinary() {
        // 模拟 PNG 文件头字节被当 Latin-1/UTF-8 误解码后混入大量控制字符/替换字符的典型乱码模式
        String corrupted = "�PNG\r\n\n   \rIHDR   P���DeXIfMM * ";
        McpSchema.TextContent text = new McpSchema.TextContent(corrupted);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(text), false);

        McpToolCallResult callResult = factory.toCallResult(result);

        assertTrue(callResult.success());
        assertEquals(corrupted, callResult.output(), "已损毁的原始文本仍应原样透出，不隐藏数据");
        assertTrue(callResult.outputLooksBinary());
    }

    /** 正常的、包含少量转义换行的多行文本/JSON 不应被误判为二进制。 */
    @Test
    void toCallResult_shouldNotFlagOutputLooksBinary_forNormalMultilineText() {
        String normal = "{\n  \"orderId\": \"20260613001\",\n  \"status\": \"SHIPPED\"\n}";
        McpSchema.TextContent text = new McpSchema.TextContent(normal);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(text), false);

        McpToolCallResult callResult = factory.toCallResult(result);

        assertFalse(callResult.outputLooksBinary());
    }

    /** 工具执行报错（协议层 isError=true）不是异常：success=false + 错误文案，图片块不透出。 */
    @Test
    void toCallResult_shouldMapIsErrorToFailure() {
        McpSchema.TextContent text = new McpSchema.TextContent("参数 orderId 非法");
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(text), true);

        McpToolCallResult callResult = factory.toCallResult(result);

        assertFalse(callResult.success());
        assertEquals("参数 orderId 非法", callResult.errorMessage());
        assertNull(callResult.output());
    }
}
