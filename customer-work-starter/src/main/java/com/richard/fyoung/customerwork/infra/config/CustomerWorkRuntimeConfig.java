package com.richard.fyoung.customerwork.infra.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 客服机器人运行时配置契约（admin 8082 发布端与 starter 8080 消费端共享的 JSON 载荷）。
 *
 * <p>admin 侧组装本对象 → 序列化为 JSON → 发布到 Nacos 配置中心；starter 侧监听同一 dataId，
 * 拉取后反序列化为本对象 → 交给 {@code RuntimeConfigApplier} 热应用（模型链热替换 + 提示词/MCP/
 * maxIters 热更新），无需重启 8080。</p>
 *
 * <p>{@code apiKeyCipher} 为 admin AES-GCM 加密后的密文（原样跨网传输，不落明文）；消费端由
 * {@code AesGcmDecryptor} 用同一密钥解密后写入模型链。字段前后兼容：{@link JsonIgnoreProperties}
 * 容忍未知字段，便于后续演进不破坏老消费端。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerWorkRuntimeConfig {

    /** 契约版本号（便于消费端做兼容处理，当前恒为 1）。 */
    private int schemaVersion = 1;

    /** 发布时间戳（ISO-8601 文本，仅审计/展示用，不参与业务逻辑）。 */
    private String publishedAt;

    /** 模型主配置。 */
    private Model model = new Model();

    /** 私有化兜底模型（可空，空表示不启用兜底）。 */
    private Fallback fallback;

    /** 模型调用重试（可空，空表示不启用重试）。 */
    private Retry retry;

    /** 系统提示词（可空，空表示不覆盖，沿用消费端内置提示词）。 */
    private String systemPrompt;

    /** MCP 远程服务列表（可空，空表示清空 MCP 接入）。 */
    private List<McpServer> mcpServers = new ArrayList<>();

    /** Agent 运行时配置。 */
    private Agent agent = new Agent();

    /** 模型主配置。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Model {
        private String provider;
        private String name;
        private String baseUrl;
        /** API Key 密文（AES-GCM，Base64(IV+cipher)）；消费端解密后使用。 */
        private String apiKeyCipher;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private Boolean stream;
    }

    /** 私有化兜底模型（明文/密文分离：兜底密钥同样走 apiKeyCipher）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Fallback {
        private boolean enabled;
        private String provider;
        private String name;
        private String baseUrl;
        private String apiKeyCipher;
    }

    /** 模型调用重试。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Retry {
        private boolean enabled;
        private int maxAttempts = 2;
        private long backoffMs = 500;
    }

    /** MCP 远程服务。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpServer {
        private String name;
        private String url;
        private String transport = "sse";
        private Map<String, String> headers;
    }

    /** Agent 运行时配置。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Agent {
        private Integer maxIters;
    }
}
