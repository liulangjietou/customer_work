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
 * @author owlzhangfq@gmail.com
 */
public record McpServerSpec(
    String name,
    String type,
    String url,
    String command,
    List<String> args,
    Map<String, String> headers) {

    /** 本地进程传输：拉起子进程用标准输入输出通信。 */
    public static final String TYPE_STDIO = "stdio";
    /** Streamable HTTP 传输。 */
    public static final String TYPE_HTTP = "http";
    /** SSE 传输（默认）。 */
    public static final String TYPE_SSE = "sse";

    /** 构造一个 http/sse 传输的规格（无 stdio 相关字段）。 */
    public static McpServerSpec remote(String name, String type, String url, Map<String, String> headers) {
        return new McpServerSpec(name, type, url, null, List.of(), headers);
    }
}
