package com.richard.fyoung.customerwork.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务连接规格：把「配置从哪来」（后台库里的一段 JSON / yml 里的一个 Server 节点）与
 * 「怎么建客户端」这两件事解耦——所有来源先归一成本规格，再交给
 * {@link McpClientFactory#buildClientBuilder(McpServerSpec)} 构建客户端。
 *
 * @param name    MCP 服务名（作为客户端标识，也是工具名前缀来源）
 * @param type    传输类型：{@link #TYPE_STDIO} / {@link #TYPE_HTTP} / {@link #TYPE_SSE}，
 *                空或无法识别时按 sse 处理（历史行为，多数远程 MCP 服务默认走 sse）
 * @param url     http/sse 传输的服务地址
 * @param command stdio 传输的启动命令
 * @param args    stdio 传输的启动参数
 * @param headers http/sse 传输的附加请求头（如 {@code Authorization}），null 表示不透传
 * @param workingDirectory stdio 子进程工作目录
 * @param environment stdio 子进程显式环境变量
 * @author owlzhangfq@gmail.com
 */
public record McpServerSpec(
    String name,
    String type,
    String url,
    String command,
    List<String> args,
    Map<String, String> headers,
    String workingDirectory,
    Map<String, String> environment) {

    /** 本地进程传输：拉起子进程用标准输入输出通信。 */
    public static final String TYPE_STDIO = "stdio";
    /** Streamable HTTP 传输。 */
    public static final String TYPE_HTTP = "http";
    /** SSE 传输（默认）。 */
    public static final String TYPE_SSE = "sse";

    /** Claude Desktop / Cursor 标准配置的服务列表包装字段。 */
    public static final String MCP_SERVERS_WRAPPER_KEY = "mcpServers";

    /**
     * 配置侧的 transport 取值，映射到 {@link #TYPE_HTTP}。
     *
     * <p>放在 type 旁边是为了让"配置写什么 → spec 里是什么"一眼可见：客服端按它选传输，
     * 后台发布配置时按它输出，两处此前各写一份字面量。</p>
     */
    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable-http";

    /** 构造一个 http/sse 传输的规格（无 stdio 相关字段）。 */
    public static McpServerSpec remote(String name, String type, String url, Map<String, String> headers) {
        return new McpServerSpec(name, type, url, null, List.of(), headers, null, Map.of());
    }

    /** 构造一个经过策略校验的 stdio 规格。 */
    public static McpServerSpec stdio(String name, String command, List<String> args,
                                      String workingDirectory, Map<String, String> environment) {
        return new McpServerSpec(name, TYPE_STDIO, null, command, args, null,
            workingDirectory, environment);
    }
}
