package com.richard.fyoung.customerwork.infra.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
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

    /** 契约版本号：v5 新增模型健康动态路由 overlay；消费端仍兼容旧正常载荷。 */
    private int schemaVersion = 5;

    /**
     * 运行目标是否允许继续服务。旧载荷缺少该字段时 Jackson 保留默认 true，保证向后兼容；
     * false 是显式 tombstone，消费端必须先关闭总闸门，不能继续沿用最后一份正常模型配置。
     */
    private Boolean active = Boolean.TRUE;

    /** 当前 dataId 对应的智能体编码；撤销载荷不再依赖已删除的数据库行，故必须随快照固化。 */
    private String targetCode;

    /** 发布时间戳（ISO-8601 文本，仅审计/展示用，不参与业务逻辑）。 */
    private String publishedAt;

    /** 每次发布唯一修订号：贯穿 admin 发布任务、Nacos 载荷与实例 ACK。 */
    private String revision;

    /** 业务配置内容摘要，用于实例 ACK 与运营侧核对实际应用的是哪份内容。 */
    private String contentHash;
    /** 签名密钥 ID，支持消费端在轮换窗口同时信任多把 key。 */
    private String signatureKeyId;
    private String signatureAlgorithm;
    private String signature;

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

    /** Agent 运行时配置；整个 section 缺失表示保持现值，存在但 maxIters 为空表示重置部署基线。 */
    private Agent agent;

    /** 绑定到当前 Agent 的不可变模型路由策略；为空时沿用 v1 主备/分级模型链。 */
    private RoutingPolicy routingPolicy;

    /** 当前 Agent 唯一 RUNNING 的双臂在线实验；为空表示不分流。 */
    private OnlineExperiment onlineExperiment;

    /** 模型主配置。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Model {
        private Long deploymentId;
        private String provider;
        private String name;
        private String baseUrl;
        /** API Key 密文（AES-GCM，Base64(IV+cipher)）；消费端解密后使用。 */
        private String apiKeyCipher;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private Boolean stream;
        private Pricing pricing;
        private HealthOverlay health;
    }

    /** 私有化兜底模型（明文/密文分离：兜底密钥同样走 apiKeyCipher）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Fallback {
        private boolean enabled;
        private Long deploymentId;
        private String provider;
        private String name;
        private String baseUrl;
        private String apiKeyCipher;
        private Pricing pricing;
        private HealthOverlay health;
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
        /** 仅兼容本地静态配置；控制面发布必须使用 headersCipher，禁止在 Nacos 保存明文。 */
        private Map<String, String> headers;
        /** MCP headers 的 AES-GCM 密文 JSON，由消费端验签、验 hash 后一次性解密。 */
        private String headersCipher;
        /** 服务端主体授权快照；消费端必须在工具执行前判定，不能仅依赖控制台 UI。 */
        private List<String> allowedSubjectTypes = new ArrayList<>();
    }

    /** Agent 运行时配置。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Agent {
        private Integer maxIters;
    }

    /**
     * 不可变模型路由快照。部署凭据仍以 AES-GCM 密文跨网传输，规则只引用 deploymentId。
     * policyContentHash 只描述规则版本；外层 contentHash 还会覆盖端点修订与部署快照。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoutingPolicy {
        private Long policyId;
        private Long versionId;
        private Integer versionNo;
        private String policyContentHash;
        /** 当前发布绑定的 Agent/渠道事实，避免运行时从不可信请求参数猜测。 */
        private Long agentId;
        private String channelCode;
        private List<RoutingDeployment> deployments = new ArrayList<>();
        private List<RoutingRule> rules = new ArrayList<>();
    }

    /** 路由候选部署快照；不包含 SecretRef 元数据，只携带消费端所需的当前密文。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoutingDeployment {
        private Long deploymentId;
        private String provider;
        private String name;
        private String baseUrl;
        private Integer endpointRevision;
        private String apiKeyCipher;
        private Pricing pricing;
        private HealthOverlay health;
    }

    /** 按 priority、ruleId 稳定排序的路由规则。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoutingRule {
        private Long ruleId;
        private String purpose;
        private Long deploymentId;
        private Integer priority;
        private RoutingCondition condition = new RoutingCondition();
    }

    /** 所有非空维度按 AND 匹配；列表维度内部按 OR 匹配。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoutingCondition {
        private List<Long> agentIds = new ArrayList<>();
        private List<String> channelCodes = new ArrayList<>();
        private Integer minInputTokens;
        private Integer maxInputTokens;
        private Boolean requiresTools;
        private Boolean requiresStructuredOutput;
        private String complexity;
    }

    /**
     * 双臂在线实验的不可变运行快照。只发布 RUNNING 实验，生命周期变化必须触发新修订发布。
     * assignmentSalt 只参与不可逆哈希分桶，不随调用日志落库。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OnlineExperiment {
        private Long experimentId;
        private Integer revision;
        private String assignmentSalt;
        /** 实验组流量，基点制 1..9999。 */
        private Integer treatmentBps;
        /** 硬截止时间（epoch ms）；消费端到点立即回基线，不等待控制面巡检。 */
        private Long expiresAtEpochMs;
        private ExperimentArm control;
        private ExperimentArm treatment;
    }

    /** 实验臂部署快照；凭据仍以 AES-GCM 密文跨网传输。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExperimentArm {
        private String arm;
        private Long deploymentId;
        private String provider;
        private String name;
        private String baseUrl;
        private Integer endpointRevision;
        private String apiKeyCipher;
        private Pricing pricing;
        private HealthOverlay health;
    }

    /**
     * 控制面健康状态的动态路由投影。缺失表示兼容旧载荷并允许路由；运行面不得自行修改该快照。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HealthOverlay {
        private String healthStatus = "UNKNOWN";
        private String effectiveHealthStatus = "UNKNOWN";
        private boolean routingAvailable = true;
        private String overrideMode = "AUTO";
        private Integer revision = 0;
        private Long cooldownUntilEpochMs;
        private Long overrideUntilEpochMs;
    }

    /** 发布时冻结的同供应商、同模型有效价目；缺价显式为 UNPRICED。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pricing {
        private String status = "UNPRICED";
        private Long priceId;
        private String currency;
        private BigDecimal inputUnitPrice;
        private BigDecimal outputUnitPrice;
        private BigDecimal cachedUnitPrice;
    }
}
