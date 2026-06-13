package com.example.customerwork.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型层配置（对应流程图"模型层 - 统一模型抽象"）。
 *
 * <p>AgentScope 提供统一的 {@link Model} 抽象，DashScope / OpenAI / Gemini / Anthropic / Ollama
 * 可以一行配置切换。这里默认接入通义千问（DashScope）。</p>
 *
 * <p>生产建议：金融/政务场景常用"国产模型 + 本地 Ollama 私有化兜底"的双模型策略，
 * 可再声明一个 {@code @Bean("fallbackModel")} 并在 Agent 失败时切换。</p>
 *
 * <p>API Key 一律从环境变量读取（切勿硬编码）：{@code export AI_DASHSCOPE_API_KEY=sk-xxx}</p>
 */
@Configuration
public class ModelConfig {

    @Value("${customer-work.model.name:qwen-max}")
    private String modelName;

    @Bean
    public Model chatModel() {
        String apiKey = System.getenv("AI_DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // 不静默失败：给出清晰的启动期错误，提示如何配置
            throw new IllegalStateException(
                "环境变量 AI_DASHSCOPE_API_KEY 未设置。请先执行：export AI_DASHSCOPE_API_KEY=你的密钥");
        }
        return DashScopeChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            // 开启流式：对应流程图⑤ streamEvents() 逐 Token 渲染
            .stream(true)
            .build();
    }
}
