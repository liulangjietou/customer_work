package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Higress AI 网关接入配置。 */
@Data
public class HigressProperties {
    /** 是否启用 Higress 接入。 */
    private boolean enabled = false;
    /** 客户端名称。 */
    private String name = "higress";
    /** Higress MCP 端点 URL。 */
    private String endpoint = "";
    /** 传输类型：sse | streamable-http。 */
    private String transport = "sse";
    /** 工具搜索关键词（Higress 按需路由工具）；留空则不启用工具搜索。 */
    private String toolSearch = "";
    /** 工具搜索返回的最大工具数。 */
    private int maxTools = 10;
    /** 连接超时（秒）。 */
    private int timeoutSeconds = 30;
}
