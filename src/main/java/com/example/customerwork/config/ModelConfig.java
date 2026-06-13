package com.example.customerwork.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 模型层配置（对应深度解析一文"模型层 - 统一模型抽象"）。
 *
 * <p>本系统以<b>阿里云百炼（DashScope，通义千问）</b>为准接入大模型。AgentScope 的
 * {@link Model} 抽象屏蔽了底层差异，后续若要切到 OpenAI / Gemini / Anthropic / Ollama，
 * 只需替换此处的 {@code Bean} 实现，业务代码零改动。</p>
 *
 * <p><b>API Key 来源（按优先级）</b>：</p>
 * <ol>
 *   <li>配置项 {@code customer-work.model.api-key}；</li>
 *   <li>环境变量 {@code DASHSCOPE_API_KEY}（生产推荐，避免密钥入库）。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(CustomerWorkProperties.class)
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    private static final String ENV_API_KEY = "DASHSCOPE_API_KEY";

    @Bean
    public Model chatModel(CustomerWorkProperties properties) {
        CustomerWorkProperties.Model cfg = properties.getModel();

        String apiKey = resolveApiKey(cfg.getApiKey());

        GenerateOptions options = GenerateOptions.builder()
            .temperature(cfg.getTemperature())
            .maxTokens(cfg.getMaxTokens())
            .build();

        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
            .apiKey(apiKey)
            .modelName(cfg.getName())
            .stream(cfg.isStream())
            .defaultOptions(options);

        // 仅当显式配置网关/兼容地址时覆盖，否则用 DashScope SDK 默认百炼地址
        if (StringUtils.hasText(cfg.getBaseUrl())) {
            builder.baseUrl(cfg.getBaseUrl());
            log.info("使用自定义模型网关地址: {}", cfg.getBaseUrl());
        }

        log.info("模型层初始化完成：provider=DashScope(百炼), model={}, stream={}",
            cfg.getName(), cfg.isStream());
        return builder.build();
    }

    private String resolveApiKey(String configured) {
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
