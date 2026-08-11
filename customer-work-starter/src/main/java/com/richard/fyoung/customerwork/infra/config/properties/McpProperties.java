package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 接入配置。 */
@Data
public class McpProperties {
    /** 是否启用 MCP 接入（把存量 HTTP 系统零改造接成 Agent 工具）。默认关闭。 */
    private boolean enabled = false;
    /** MCP 服务列表。 */
    private List<Server> servers = new ArrayList<>();

    @Data
    public static class Server {
        private String name;
        private String url;
        /** 传输类型：sse | streamable-http。 */
        private String transport = "sse";
        /** 附加请求头（如 Authorization: Bearer xxx），接需要鉴权的远程 MCP 服务时配置。 */
        private java.util.Map<String, String> headers;
    }
}
