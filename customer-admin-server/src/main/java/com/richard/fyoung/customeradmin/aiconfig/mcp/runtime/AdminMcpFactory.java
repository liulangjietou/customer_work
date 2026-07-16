package com.richard.fyoung.customeradmin.aiconfig.mcp.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugImageVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 客户端构建：把"mcpType + config JSON" -> {@link McpClientBuilder} 的解析逻辑收在一处，
 * 供 {@code AdminAgentInstanceFactory}（真实注册进 Toolkit）与本类自身的连通性测试复用，
 * 不重复实现两遍。
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminMcpFactory {

    private static final Logger log = LoggerFactory.getLogger(AdminMcpFactory.class);

    private static final String MCP_TYPE_STDIO = "stdio";
    private static final String MCP_TYPE_HTTP = "http";
    /** 单次探测超时（对齐模型连通性测试的 5~10s 区间）。 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private static final String MCP_SERVERS_WRAPPER_KEY = "mcpServers";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 按 mcpType/config 构建一个尚未连接的 {@link McpClientBuilder}。支持 stdio / sse / http 三种传输。
     *
     * <p>http/sse 传输额外透传 config 里的 {@code headers} 对象（如 {@code Authorization}）——
     * 需要鉴权的远程 MCP 服务（Bearer token 等）没有这层透传会在握手时被拒（401），
     * 调试面板、连通性测试与真实注册路径共用本方法，一处透传三处生效。</p>
     */
    public McpClientBuilder buildClientBuilder(String mcpName, String mcpType, String config) throws Exception {
        JsonNode node = unwrapMcpServers(objectMapper.readTree(config));
        McpClientBuilder builder = McpClientBuilder.create(mcpName);
        String type = mcpType == null ? "" : mcpType.toLowerCase();
        switch (type) {
            case MCP_TYPE_STDIO -> {
                String command = node.path("command").asText();
                List<String> args = objectMapper.convertValue(node.path("args"), List.class);
                builder.stdioTransport(command, (args == null ? List.<String>of() : args).toArray(new String[0]));
            }
            case MCP_TYPE_HTTP -> {
                builder.streamableHttpTransport(node.path("url").asText());
                applyHeaders(builder, node);
            }
            default -> {
                builder.sseTransport(node.path("url").asText());
                applyHeaders(builder, node);
            }
        }
        return builder;
    }

    /** config 里配置了 headers 对象时透传给 HTTP 请求（值统一按字符串取，兼容用户误写数字/布尔）。 */
    private void applyHeaders(McpClientBuilder builder, JsonNode node) {
        JsonNode headersNode = node.path("headers");
        if (!headersNode.isObject() || headersNode.isEmpty()) {
            return;
        }
        Map<String, String> headers = objectMapper.convertValue(headersNode, new TypeReference<Map<String, String>>() {});
        builder.headers(headers);
    }

    /** 调试面板手工操作，比连通性探测多给点耐心（选工具、填参数、点调用，人手速度比探测慢）。 */
    private static final Duration DEBUG_TIMEOUT = Duration.ofSeconds(15);

    /** 尝试建立连接并列出工具，验证 MCP 服务可达；用完即关闭，不缓存实例（与真实注册用途区分）。 */
    public McpTestResult testConnectivity(String mcpName, String mcpType, String config) {
        LocalDateTime now = LocalDateTime.now();
        McpClientWrapper wrapper = null;
        try {
            wrapper = connectAndInitialize(mcpName, mcpType, config, TEST_TIMEOUT);
            wrapper.listTools().block(TEST_TIMEOUT);
            return new McpTestResult(McpTestResult.STATUS_SUCCESS, now, null);
        } catch (Exception e) {
            log.error("mcp connectivity test failed, code={}, mcpName={}", "MCP-TEST-FAIL", mcpName, e);
            return new McpTestResult(McpTestResult.STATUS_FAILED, now, truncate(e.getMessage()));
        } finally {
            closeQuietly(wrapper, mcpName);
        }
    }

    /** 调试面板：连接并列出该 MCP 提供的全部工具（含 inputSchema，供前端动态渲染参数表单）。 */
    public List<McpDebugToolVO> listDebugTools(String mcpName, String mcpType, String config) throws Exception {
        McpClientWrapper wrapper = null;
        try {
            wrapper = connectAndInitialize(mcpName, mcpType, config, DEBUG_TIMEOUT);
            List<McpSchema.Tool> tools = wrapper.listTools().block(DEBUG_TIMEOUT);
            return tools == null ? List.of() : tools.stream().map(this::toDebugToolVo).collect(Collectors.toList());
        } finally {
            closeQuietly(wrapper, mcpName);
        }
    }

    /** 调试面板：单次调用一个工具并返回结果；连接/调用异常都由调用方（Service 层）统一兜底成失败结果，这里直接往外抛。 */
    public McpDebugCallResult callDebugTool(String mcpName, String mcpType, String config,
                                             String toolName, Map<String, Object> arguments) throws Exception {
        McpClientWrapper wrapper = null;
        try {
            wrapper = connectAndInitialize(mcpName, mcpType, config, DEBUG_TIMEOUT);
            McpSchema.CallToolResult result = wrapper.callTool(toolName, arguments == null ? Map.of() : arguments)
                .block(DEBUG_TIMEOUT);
            return toCallResult(result);
        } finally {
            closeQuietly(wrapper, mcpName);
        }
    }

    /** buildAsync() 只构造客户端对象、不建立连接——listTools()/callTool() 前必须先 initialize() 完成
     * MCP 握手，否则 SDK 抛 IllegalStateException("MCP client '...' not initialized")；与
     * Toolkit#registerMcpClient（真实注册路径）内部的调用顺序保持一致。 */
    private McpClientWrapper connectAndInitialize(String mcpName, String mcpType, String config, Duration timeout) throws Exception {
        McpClientWrapper wrapper = buildClientBuilder(mcpName, mcpType, config)
            .timeout(timeout)
            .buildAsync()
            .block(timeout);
        if (wrapper == null) {
            throw new IllegalStateException("构建 MCP 客户端失败");
        }
        wrapper.initialize().block(timeout);
        return wrapper;
    }

    private void closeQuietly(McpClientWrapper wrapper, String mcpName) {
        if (wrapper == null) {
            return;
        }
        try {
            wrapper.close();
        } catch (Exception e) {
            log.error("mcp client close failed, code={}, mcpName={}", "MCP-CLIENT-CLOSE-FAIL", mcpName, e);
        }
    }

    private McpDebugToolVO toDebugToolVo(McpSchema.Tool tool) {
        McpSchema.JsonSchema schema = tool.inputSchema();
        if (schema == null) {
            return new McpDebugToolVO(tool.name(), tool.description(), "object", Map.of(), List.of());
        }
        return new McpDebugToolVO(tool.name(), tool.description(), schema.type(),
            schema.properties() == null ? Map.of() : schema.properties(),
            schema.required() == null ? List.of() : schema.required());
    }

    /** 判定"疑似二进制内容被误当文本解码"的字符占比阈值——正常文本/代码/JSON 里控制字符占比接近 0，
     * 二进制文件（如图片）被误按文本解码后控制字符/替换字符占比通常远高于此。 */
    private static final double BINARY_TEXT_SUSPECT_RATIO = 0.05;
    private static final int BINARY_TEXT_MIN_LENGTH = 20;

    /**
     * {@code isError=true} 是"工具执行报错但协议调用成功"（如参数非法、下游查询无结果），不是异常，仍走成功返回。
     *
     * <p>MCP 协议的内容块是多类型的（{@code TextContent}/{@code ImageContent}/{@code AudioContent}/
     * 资源引用等），早期实现只提取 {@code TextContent} 会导致图片类工具调用结果被静默丢弃——用户看到的
     * 是空白输出。这里把 {@code ImageContent} 单独提取为 {@link McpDebugImageVO} 列表交给前端渲染成
     * {@code <img>}，不再和文本混在一起硬塞进纯文本展示区。</p>
     *
     * <p>另一类不同的问题：部分通用型 MCP 工具（如文件系统 server 的 {@code read_file}）不区分文件是否
     * 为二进制，把图片等二进制文件内容当文本读出、通过 {@code TextContent}（而非 {@code ImageContent}）
     * 返回——此时二进制字节在 MCP 服务端那一步已经损毁，我们收到的就已经是乱码，无法还原原始字节；
     * 用 {@link #looksLikeBinaryText} 识别这种模式，让前端换成清晰提示而不是原样展示一坨乱码。</p>
     */
    private McpDebugCallResult toCallResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return new McpDebugCallResult(false, null, "工具未返回任何结果", List.of(), false);
        }
        List<McpSchema.Content> content = result.content() == null ? List.of() : result.content();
        String text = content.stream()
            .filter(McpSchema.TextContent.class::isInstance)
            .map(McpSchema.TextContent.class::cast)
            .map(McpSchema.TextContent::text)
            .collect(Collectors.joining("\n"));
        List<McpDebugImageVO> images = content.stream()
            .filter(McpSchema.ImageContent.class::isInstance)
            .map(McpSchema.ImageContent.class::cast)
            .map(img -> new McpDebugImageVO(img.mimeType(), img.data()))
            .collect(Collectors.toList());
        boolean isError = Boolean.TRUE.equals(result.isError());
        return isError
            ? new McpDebugCallResult(false, null, StringUtils.hasText(text) ? text : "工具执行失败", List.of(), false)
            : new McpDebugCallResult(true, text, null, images, looksLikeBinaryText(text));
    }

    /** 控制字符（除 \t\n\r 外）或 Unicode 替换字符 U+FFFD 占比超阈值，判定为"疑似二进制内容被误当文本解码"。 */
    private boolean looksLikeBinaryText(String text) {
        if (text == null || text.length() < BINARY_TEXT_MIN_LENGTH) {
            return false;
        }
        int suspicious = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD' || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                suspicious++;
            }
        }
        return suspicious > text.length() * BINARY_TEXT_SUSPECT_RATIO;
    }

    /**
     * 兼容 Claude Desktop / Cursor 等主流 MCP 客户端的标准配置格式——外层包一层
     * {@code {"mcpServers": {"任意名字": {实际配置}}}}。这个格式是各家 MCP 服务文档默认给的抄样，
     * 用户直接照抄大概率带这层包装；本系统的 {@code ai_mcp.mcp_name} 字段已经是独立录入的显示名，
     * 不需要读 wrapper 里的 key，直接取里面唯一一个 server 条目的配置对象即可。没有这层包装时按
     * 原样返回，两种格式都认。
     */
    private JsonNode unwrapMcpServers(JsonNode node) {
        JsonNode servers = node.path(MCP_SERVERS_WRAPPER_KEY);
        if (servers.isObject() && servers.size() > 0) {
            return servers.elements().next();
        }
        return node;
    }

    private String truncate(String text) {
        if (text == null) {
            return "unknown error";
        }
        return text.length() > MAX_ERROR_MESSAGE_LENGTH ? text.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : text;
    }
}
