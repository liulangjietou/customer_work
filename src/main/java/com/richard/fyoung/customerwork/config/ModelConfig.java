package com.richard.fyoung.customerwork.config;

import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 模型层配置（对应「模型层 - 统一模型抽象 + 多模型 + 私有化兜底」）。
 *
 * <p>按 {@code customer-work.model.provider} 接入 dashscope（百炼）/ openai / anthropic / gemini /
 * ollama 任一厂商；开启 {@code model.fallback} 后用 {@link FallbackChatModel} 包一层私有化兜底。
 * 所有差异被 {@link Model} 抽象屏蔽，业务代码零改动。</p>
 *
 * <p>API Key 来源（按优先级）：配置项 {@code customer-work.model.api-key} → 环境变量 {@code DASHSCOPE_API_KEY}。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(CustomerWorkProperties.class)
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    private static final String ENV_API_KEY = "DASHSCOPE_API_KEY";

    @Bean
    public Model chatModel(CustomerWorkProperties properties) {
        CustomerWorkProperties.Model cfg = properties.getModel();
        Model primary = buildPrimary(cfg);

        CustomerWorkProperties.Model.Fallback fb = cfg.getFallback();
        if (fb.isEnabled()) {
            Model fallback = buildByProvider(fb.getProvider(), fb.getName(),
                fb.getApiKey(), fb.getBaseUrl(), cfg);
            log.info("已启用私有化兜底：主 {} -> 兜底 {}({})",
                primary.getModelName(), fb.getProvider(), fb.getName());
            return new FallbackChatModel(primary, fallback);
        }
        return primary;
    }

    private Model buildPrimary(CustomerWorkProperties.Model cfg) {
        String apiKey = "dashscope".equalsIgnoreCase(cfg.getProvider())
            ? resolveDashScopeKey(cfg.getApiKey())
            : cfg.getApiKey();
        Model model = buildByProvider(cfg.getProvider(), cfg.getName(), apiKey, cfg.getBaseUrl(), cfg);
        log.info("模型层初始化完成：provider={}, model={}, stream={}",
            cfg.getProvider(), cfg.getName(), cfg.isStream());
        return model;
    }

    /** 高级生成参数（跨厂商统一）：温度 / topP / maxTokens / 推理强度。 */
    GenerateOptions buildOptions(CustomerWorkProperties.Model cfg) {
        GenerateOptions.Builder b = GenerateOptions.builder()
            .temperature(cfg.getTemperature())
            .maxTokens(cfg.getMaxTokens());
        if (cfg.getTopP() != null) {
            b.topP(cfg.getTopP());
        }
        if (StringUtils.hasText(cfg.getReasoningEffort())) {
            b.reasoningEffort(cfg.getReasoningEffort());
        }
        return b.build();
    }

    /** 按厂商构建模型。stream 与高级生成参数对各厂商统一应用。 */
    Model buildByProvider(String provider, String name, String apiKey, String baseUrl,
                          CustomerWorkProperties.Model cfg) {
        String p = provider == null ? "dashscope" : provider.toLowerCase();
        GenerateOptions options = buildOptions(cfg);
        switch (p) {
            case "openai": {
                OpenAIChatModel.Builder b = OpenAIChatModel.builder()
                    .apiKey(apiKey).modelName(name).stream(cfg.isStream()).generateOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case "anthropic": {
                AnthropicChatModel.Builder b = AnthropicChatModel.builder()
                    .apiKey(apiKey).modelName(name).stream(cfg.isStream()).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case "gemini": {
                // 注意：Gemini 需额外引入 google-genai 依赖
                GeminiChatModel.Builder b = GeminiChatModel.builder()
                    .apiKey(apiKey).modelName(name).streamEnabled(cfg.isStream()).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case "ollama": {
                // Ollama 本地私有化：使用 OllamaOptions（生成参数另行配置），默认 localhost:11434
                OllamaChatModel.Builder b = OllamaChatModel.builder().modelName(name);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            default: {
                DashScopeChatModel.Builder b = DashScopeChatModel.builder()
                    .apiKey(apiKey).modelName(name).stream(cfg.isStream()).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                if (cfg.getEnableSearch() != null) {
                    b.enableSearch(cfg.getEnableSearch());
                }
                if (cfg.getEnableThinking() != null) {
                    b.enableThinking(cfg.getEnableThinking());
                }
                return b.build();
            }
        }
    }

    private String resolveDashScopeKey(String configured) {
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        String fromEnv = System.getenv(ENV_API_KEY);
        if (StringUtils.hasText(fromEnv)) {
            return fromEnv.trim();
        }
        throw new IllegalStateException(
            "未找到百炼 API Key。请配置 customer-work.model.api-key，"
          + "或设置环境变量 " + ENV_API_KEY + "=你的密钥");
    }
}
