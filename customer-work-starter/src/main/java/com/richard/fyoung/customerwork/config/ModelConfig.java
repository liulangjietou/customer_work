package com.richard.fyoung.customerwork.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public Model chatModel(CustomerWorkProperties properties) {
        CustomerWorkProperties.Model cfg = properties.getModel();
        Model primary = buildPrimary(cfg);

        Model model = primary;
        CustomerWorkProperties.Model.Fallback fb = cfg.getFallback();
        if (fb.isEnabled()) {
            Model fallback = buildByProvider(fb.getProvider(), fb.getName(),
                fb.getApiKey(), fb.getBaseUrl(), cfg);
            log.info("已启用私有化兜底：主 {} -> 兜底 {}({})",
                primary.getModelName(), fb.getProvider(), fb.getName());
            model = new FallbackChatModel(primary, fallback);
        }

        CustomerWorkProperties.Model.Retry retry = cfg.getRetry();
        if (retry.isEnabled()) {
            log.info("已启用模型调用重试：maxAttempts={}, backoffMs={}",
                retry.getMaxAttempts(), retry.getBackoffMs());
            model = new ResilientChatModel(model, retry.getMaxAttempts(), retry.getBackoffMs());
        }
        return model;
    }

    private Model buildPrimary(CustomerWorkProperties.Model cfg) {
        String apiKey = "dashscope".equalsIgnoreCase(cfg.getProvider())
            ? ChatModelFactory.resolveDashScopeKey(cfg.getApiKey())
            : cfg.getApiKey();
        Model model = buildByProvider(cfg.getProvider(), cfg.getName(), apiKey, cfg.getBaseUrl(), cfg);
        log.info("模型层初始化完成：provider={}, model={}, stream={}",
            cfg.getProvider(), cfg.getName(), cfg.isStream());
        return model;
    }

    /** 高级生成参数（跨厂商统一）：委托 {@link ChatModelFactory#buildOptions}。 */
    GenerateOptions buildOptions(CustomerWorkProperties.Model cfg) {
        return ChatModelFactory.buildOptions(cfg);
    }

    /** 按厂商构建模型：委托 {@link ChatModelFactory#build}，stream 与高级生成参数取自 {@code cfg}。 */
    Model buildByProvider(String provider, String name, String apiKey, String baseUrl,
                          CustomerWorkProperties.Model cfg) {
        return ChatModelFactory.build(provider, name, apiKey, baseUrl, cfg.isStream(),
            ChatModelFactory.buildOptions(cfg), cfg.getEnableSearch(), cfg.getEnableThinking());
    }
}
