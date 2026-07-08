package com.richard.fyoung.customeradmin.aiconfig.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 按 mcpType/config 构建一个尚未连接的 {@link McpClientBuilder}。支持 stdio / sse / http 三种传输。 */
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
            case MCP_TYPE_HTTP -> builder.streamableHttpTransport(node.path("url").asText());
            default -> builder.sseTransport(node.path("url").asText());
        }
        return builder;
    }

    /** 尝试建立连接并列出工具，验证 MCP 服务可达；用完即关闭，不缓存实例（与真实注册用途区分）。 */
    public McpTestResult testConnectivity(String mcpName, String mcpType, String config) {
        LocalDateTime now = LocalDateTime.now();
        McpClientWrapper wrapper = null;
        try {
            wrapper = buildClientBuilder(mcpName, mcpType, config)
                .timeout(TEST_TIMEOUT)
                .buildAsync()
                .block(TEST_TIMEOUT);
            if (wrapper == null) {
                return new McpTestResult(McpTestResult.STATUS_FAILED, now, "构建 MCP 客户端失败");
            }
            // buildAsync() 只构造客户端对象，不建立连接——listTools() 前必须先 initialize()
            // 完成 MCP 握手，否则 SDK 抛 IllegalStateException("MCP client '...' not initialized")；
            // 与 Toolkit#registerMcpClient（真实注册路径）内部的调用顺序保持一致。
            wrapper.initialize().block(TEST_TIMEOUT);
            wrapper.listTools().block(TEST_TIMEOUT);
            return new McpTestResult(McpTestResult.STATUS_SUCCESS, now, null);
        } catch (Exception e) {
            log.error("mcp connectivity test failed, code={}, mcpName={}", "MCP-TEST-FAIL", mcpName, e);
            return new McpTestResult(McpTestResult.STATUS_FAILED, now, truncate(e.getMessage()));
        } finally {
            if (wrapper != null) {
                try {
                    wrapper.close();
                } catch (Exception e) {
                    log.error("mcp test client close failed, code={}, mcpName={}", "MCP-TEST-CLOSE-FAIL", mcpName, e);
                }
            }
        }
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
