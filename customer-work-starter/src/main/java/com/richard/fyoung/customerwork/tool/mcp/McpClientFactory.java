package com.richard.fyoung.customerwork.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 客户端解析与构建核心：把「传输类型 + 配置」-&gt; {@link McpClientBuilder} 的解析逻辑、
 * 连通性探测、调试用的列工具/调工具收在一处，供所有需要接入 MCP 的场景复用
 * （yml 静态配置的 {@code McpToolkitConfigurer}、后台管理的库配置与调试面板等），不重复实现多遍。
 *
 * <p>本类是无状态纯工具类，不注册为 Spring Bean——需要的模块自行 {@code new} 并按自己的装配方式持有。</p>
 *
 * <p>方法分两层：{@link #buildClientBuilder(McpServerSpec)} 只做「规格 -&gt; 客户端」，
 * 不设超时也不发起连接（超时由调用方按用途决定：启动期注册、连通性探测、人工调试三者耐心不同）；
 * {@link #testConnectivity}/{@link #listTools}/{@link #callTool} 则是自带连接与关闭的一次性操作。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class McpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(McpClientFactory.class);

    /** 单次探测超时（对齐模型连通性测试的 5~10s 区间）。 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(8);
    /** 调试面板手工操作，比连通性探测多给点耐心（选工具、填参数、点调用，人手速度比探测慢）。 */
    private static final Duration DEBUG_TIMEOUT = Duration.ofSeconds(15);

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    /** 判定"疑似二进制内容被误当文本解码"的字符占比阈值——正常文本/代码/JSON 里控制字符占比接近 0，
     * 二进制文件（如图片）被误按文本解码后控制字符/替换字符占比通常远高于此。 */
    private static final double BINARY_TEXT_SUSPECT_RATIO = 0.05;
    private static final int BINARY_TEXT_MIN_LENGTH = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpSecurityPolicy securityPolicy;

    /** 默认只允许公网远程 MCP，stdio 保持关闭。 */
    public McpClientFactory() {
        this(McpSecurityPolicy.strict());
    }

    public McpClientFactory(McpSecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

    /**
     * 解析一段 MCP 配置 JSON 为 {@link McpServerSpec}。
     *
     * <p>兼容两种写法：裸的配置对象，以及 Claude Desktop / Cursor 等主流 MCP 客户端的标准格式
     * （外层包一层 {@code mcpServers}，见 {@link #unwrapMcpServers}）。</p>
     */
    public McpServerSpec parseSpec(String mcpName, String mcpType, String config) throws Exception {
        JsonNode node = unwrapMcpServers(objectMapper.readTree(config));
        String type = mcpType == null ? "" : mcpType.toLowerCase();
        if (McpServerSpec.TYPE_STDIO.equals(type)) {
            List<String> args = objectMapper.convertValue(node.path("args"), new TypeReference<List<String>>() {});
            McpServerSpec spec = McpServerSpec.stdio(mcpName, node.path("command").asText(),
                args == null ? List.of() : args, workingDirectory(node), parseEnvironment(node));
            return securityPolicy.validateStdio(spec);
        }
        // http 之外的一切（含空值、未知值）都按 sse 处理，与历史行为一致
        String remoteType = McpServerSpec.TYPE_HTTP.equals(type) ? McpServerSpec.TYPE_HTTP : McpServerSpec.TYPE_SSE;
        return McpServerSpec.remote(mcpName, remoteType,
            securityPolicy.validateRemoteUrl(node.path("url").asText()), parseHeaders(node));
    }

    /** 按 mcpType/config JSON 构建一个尚未连接、也尚未设置超时的 {@link McpClientBuilder}。 */
    public McpClientBuilder buildClientBuilder(String mcpName, String mcpType, String config) throws Exception {
        return buildClientBuilder(parseSpec(mcpName, mcpType, config));
    }

    /**
     * 按连接规格构建一个尚未连接的 {@link McpClientBuilder}。支持 stdio / sse / http 三种传输。
     *
     * <p>http/sse 传输额外透传规格里的 {@code headers}（如 {@code Authorization}）——需要鉴权的远程
     * MCP 服务（Bearer token 等）没有这层透传会在握手时被拒（401）；调试面板、连通性测试与真实注册路径
     * 共用本方法，一处透传处处生效。</p>
     */
    public McpClientBuilder buildClientBuilder(McpServerSpec spec) {
        McpClientBuilder builder = McpClientBuilder.create(spec.name());
        String type = spec.type() == null ? "" : spec.type().toLowerCase();
        switch (type) {
            case McpServerSpec.TYPE_STDIO -> {
                McpServerSpec safeSpec = securityPolicy.validateStdio(spec);
                McpStdioProcessLauncher.LaunchCommand launch = McpStdioProcessLauncher.commandFor(safeSpec);
                builder.stdioTransport(launch.command(), launch.arguments(), launch.environment());
            }
            case McpServerSpec.TYPE_HTTP -> {
                String safeUrl = securityPolicy.validateRemoteUrl(spec.url());
                builder.streamableHttpTransport(safeUrl)
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                        securityPolicy.validateRequestTarget(uri));
                applyHeaders(builder, spec.headers());
            }
            default -> {
                String safeUrl = securityPolicy.validateRemoteUrl(spec.url());
                builder.sseTransport(safeUrl)
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                        securityPolicy.validateRequestTarget(uri));
                applyHeaders(builder, spec.headers());
            }
        }
        return builder;
    }

    /** 尝试建立连接并列出工具，验证 MCP 服务可达；用完即关闭，不缓存实例（与真实注册用途区分）。 */
    public McpConnectivityResult testConnectivity(String mcpName, String mcpType, String config) {
        LocalDateTime now = LocalDateTime.now();
        McpClientWrapper wrapper = null;
        try {
            wrapper = connectAndInitialize(mcpName, mcpType, config, TEST_TIMEOUT);
            wrapper.listTools().block(TEST_TIMEOUT);
            return new McpConnectivityResult(true, now, null);
        } catch (Exception e) {
            log.error("mcp connectivity test failed, code={}, mcpName={}", "MCP-TEST-FAIL", mcpName, e);
            return new McpConnectivityResult(false, now, truncate(e.getMessage()));
        } finally {
            closeQuietly(wrapper, mcpName);
        }
    }

    /** 连接并列出该 MCP 提供的全部工具（含 inputSchema，供调用方动态渲染参数表单）。 */
    public List<McpToolDescriptor> listTools(String mcpName, String mcpType, String config) throws Exception {
        McpClientWrapper wrapper = null;
        try {
            wrapper = connectAndInitialize(mcpName, mcpType, config, DEBUG_TIMEOUT);
            List<McpSchema.Tool> tools = wrapper.listTools().block(DEBUG_TIMEOUT);
            return tools == null ? List.of() : tools.stream().map(this::toToolDescriptor).collect(Collectors.toList());
        } finally {
            closeQuietly(wrapper, mcpName);
        }
    }

    /** 单次调用一个工具并返回结果；连接/调用异常直接往外抛，由调用方按自己的错误体系统一兜底。 */
    public McpToolCallResult callTool(String mcpName, String mcpType, String config,
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

    /** config 里配置了 headers 时透传给 HTTP 请求。 */
    private void applyHeaders(McpClientBuilder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        builder.headers(headers);
    }

    /** 读 config 里的 headers 对象（值统一按字符串取，兼容用户误写数字/布尔）。 */
    private Map<String, String> parseHeaders(JsonNode node) {
        JsonNode headersNode = node.path("headers");
        if (!headersNode.isObject() || headersNode.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(headersNode, new TypeReference<Map<String, String>>() {});
    }

    private Map<String, String> parseEnvironment(JsonNode node) {
        JsonNode environmentNode = node.path("env");
        if (!environmentNode.isObject() || environmentNode.isEmpty()) {
            return Map.of();
        }
        Map<String, String> environment = objectMapper.convertValue(environmentNode,
            new TypeReference<LinkedHashMap<String, String>>() {});
        return environment == null ? Map.of() : environment;
    }

    private String workingDirectory(JsonNode node) {
        String resolved = null;
        for (String key : List.of("workingDirectory", "workingDir", "cwd")) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isTextual()) {
                throw new IllegalArgumentException("stdio MCP workingDirectory 必须是字符串");
            }
            if (resolved != null && !resolved.equals(value.textValue())) {
                throw new IllegalArgumentException("stdio MCP 不能配置相互冲突的工作目录");
            }
            resolved = value.textValue();
        }
        return resolved;
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

    private McpToolDescriptor toToolDescriptor(McpSchema.Tool tool) {
        McpSchema.JsonSchema schema = tool.inputSchema();
        if (schema == null) {
            return new McpToolDescriptor(tool.name(), tool.description(), "object", Map.of(), List.of());
        }
        return new McpToolDescriptor(tool.name(), tool.description(), schema.type(),
            schema.properties() == null ? Map.of() : schema.properties(),
            schema.required() == null ? List.of() : schema.required());
    }

    /**
     * {@code isError=true} 是"工具执行报错但协议调用成功"（如参数非法、下游查询无结果），不是异常，仍走正常返回。
     *
     * <p>MCP 协议的内容块是多类型的（{@code TextContent}/{@code ImageContent}/{@code AudioContent}/
     * 资源引用等），只提取 {@code TextContent} 会导致图片类工具调用结果被静默丢弃——调用方看到的是空白输出。
     * 这里把 {@code ImageContent} 单独提取为 {@link McpImageContent} 列表，不和文本混在一起。</p>
     *
     * <p>另一类不同的问题：部分通用型 MCP 工具（如文件系统 server 的 {@code read_file}）不区分文件是否
     * 为二进制，把图片等二进制文件内容当文本读出、通过 {@code TextContent}（而非 {@code ImageContent}）
     * 返回——此时二进制字节在 MCP 服务端那一步已经损毁，我们收到的就已经是乱码，无法还原原始字节；
     * 用 {@link #looksLikeBinaryText} 识别这种模式，让调用方换成清晰提示而不是原样展示一坨乱码。</p>
     *
     * <p>包级可见：同包单测直接调用，无需反射。</p>
     */
    McpToolCallResult toCallResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return new McpToolCallResult(false, null, "工具未返回任何结果", List.of(), false);
        }
        List<McpSchema.Content> content = result.content() == null ? List.of() : result.content();
        String text = content.stream()
            .filter(McpSchema.TextContent.class::isInstance)
            .map(McpSchema.TextContent.class::cast)
            .map(McpSchema.TextContent::text)
            .collect(Collectors.joining("\n"));
        List<McpImageContent> images = content.stream()
            .filter(McpSchema.ImageContent.class::isInstance)
            .map(McpSchema.ImageContent.class::cast)
            .map(img -> new McpImageContent(img.mimeType(), img.data()))
            .collect(Collectors.toList());
        boolean isError = Boolean.TRUE.equals(result.isError());
        return isError
            ? new McpToolCallResult(false, null, StringUtils.hasText(text) ? text : "工具执行失败", List.of(), false)
            : new McpToolCallResult(true, text, null, images, looksLikeBinaryText(text));
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
     * 用户直接照抄大概率带这层包装；服务名由调用方独立传入（如后台的 {@code ai_mcp.mcp_name}），
     * 不需要读 wrapper 里的 key，直接取里面唯一一个 server 条目的配置对象即可。没有这层包装时按
     * 原样返回，两种格式都认。
     */
    private JsonNode unwrapMcpServers(JsonNode node) {
        JsonNode servers = node.path(McpServerSpec.MCP_SERVERS_WRAPPER_KEY);
        if (servers.isObject() && servers.size() > 0) {
            if (servers.size() != 1) {
                throw new IllegalArgumentException("mcpServers 只能包含一个服务配置");
            }
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
