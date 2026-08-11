package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型层配置。生产建议用环境变量 {@code DASHSCOPE_API_KEY} 注入密钥。 */
@Data
public class ModelProperties {
    /** 模型厂商：dashscope（百炼）| openai | anthropic | gemini | ollama。 */
    private String provider = "dashscope";
    private String apiKey;
    private String name = "qwen-max";
    private String baseUrl;
    private Double temperature = 0.3;
    private Integer maxTokens = 1500;
    private boolean stream = true;
    /** 采样 topP（GenerateOptions 高级）。 */
    private Double topP;
    /** 推理强度（reasoning effort）：low / medium / high（GenerateOptions 高级）。 */
    private String reasoningEffort;
    /** DashScope 联网搜索（仅 dashscope 生效）。 */
    private Boolean enableSearch;
    /** DashScope 深度思考（仅 dashscope 生效）。 */
    private Boolean enableThinking;
    /** Embedding 模型名（RAG 接百炼向量检索时使用）。 */
    private String embeddingName = "text-embedding-v3";
    /** 单次请求 token 用量告警阈值（0=关闭）；超过则可观测 Hook 打 WARN，便于成本护栏。 */
    private int tokenWarnThreshold = 0;
    /** 成本熔断配置（按时间窗口限制 token 消耗量）。 */
    private final CostControl costControl = new CostControl();
    /** 私有化兜底：主模型失败时切换到兜底模型。 */
    private final Fallback fallback = new Fallback();
    /** 模型调用重试（瞬时错误指数退避，提升高可用）。 */
    private final Retry retry = new Retry();

    @Data
    public static class Retry {
        private boolean enabled = false;
        private int maxAttempts = 2;
        private long backoffMs = 500;
    }

    @Data
    public static class Fallback {
        private boolean enabled = false;
        private String provider = "ollama";
        private String name = "qwen2.5";
        private String apiKey = "";
        private String baseUrl = "";
    }

    /** 成本熔断：按时间窗口限制 token 消耗量，超限拒绝请求防刷量打爆成本。 */
    @Data
    public static class CostControl {
        /** 是否启用成本熔断。 */
        private boolean enabled = false;
        /** 每分钟最大 token 消耗量；超过则熔断拒绝请求。 */
        private int maxTokensPerMinute = 100_000;
        /** 每小时最大 token 消耗量；超过则熔断。 */
        private int maxTokensPerHour = 1_000_000;
    }
}
