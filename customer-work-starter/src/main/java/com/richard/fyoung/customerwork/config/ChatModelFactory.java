package com.richard.fyoung.customerwork.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.util.StringUtils;

/**
 * 模型构建工厂（从 {@link ModelConfig} 抽取的公共能力，纯静态、无 Spring 依赖）。
 *
 * <p>把「按厂商构建 {@link Model}」「高级生成参数映射」「百炼 Key 解析」三段逻辑集中于此，
 * 供 {@link ModelConfig}（主对话模型）与附件域的视觉 OCR 模型复用，避免各处复制五路 provider 分支。
 * 行为与抽取前 {@code ModelConfig} 逐分支等价。</p>
 *
 * <p>支持 dashscope（百炼）/ openai / anthropic / gemini / ollama 五个厂商；provider 为空按 dashscope 兜底。</p>
 * @author owlzhangfq@gmail.com
 */
public final class ChatModelFactory {

    /** 百炼 API Key 的兜底环境变量名。 */
    private static final String ENV_DASHSCOPE_API_KEY = "DASHSCOPE_API_KEY";

    private static final String PROVIDER_DASHSCOPE = "dashscope";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_ANTHROPIC = "anthropic";
    private static final String PROVIDER_GEMINI = "gemini";
    private static final String PROVIDER_OLLAMA = "ollama";

    private ChatModelFactory() {
    }

    /**
     * 按厂商构建模型。stream 开关与高级生成参数对各厂商统一应用；{@code baseUrl} 有值才覆盖（否则用厂商默认端点）。
     *
     * @param provider       厂商标识（大小写不敏感，空则 dashscope）
     * @param modelName      模型名
     * @param apiKey         鉴权密钥（ollama 本地私有化不需要）
     * @param baseUrl        自定义端点（可空）
     * @param stream         是否流式
     * @param options        高级生成参数（温度 / topP / maxTokens / 推理强度）
     * @param enableSearch   DashScope 联网搜索（仅 dashscope 生效，可空）
     * @param enableThinking DashScope 深度思考（仅 dashscope 生效，可空）
     */
    public static Model build(String provider, String modelName, String apiKey, String baseUrl,
                              boolean stream, GenerateOptions options,
                              Boolean enableSearch, Boolean enableThinking) {
        String p = provider == null ? PROVIDER_DASHSCOPE : provider.toLowerCase();
        switch (p) {
            case PROVIDER_OPENAI: {
                OpenAIChatModel.Builder b = OpenAIChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(stream).generateOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case PROVIDER_ANTHROPIC: {
                AnthropicChatModel.Builder b = AnthropicChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(stream).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case PROVIDER_GEMINI: {
                // 注意：Gemini 需额外引入 google-genai 依赖
                GeminiChatModel.Builder b = GeminiChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).streamEnabled(stream).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case PROVIDER_OLLAMA: {
                // Ollama 本地私有化：使用 OllamaOptions（生成参数另行配置），默认 localhost:11434
                OllamaChatModel.Builder b = OllamaChatModel.builder().modelName(modelName);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            default: {
                DashScopeChatModel.Builder b = DashScopeChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(stream).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                if (enableSearch != null) {
                    b.enableSearch(enableSearch);
                }
                if (enableThinking != null) {
                    b.enableThinking(enableThinking);
                }
                return b.build();
            }
        }
    }

    /** 高级生成参数（跨厂商统一）：温度 / topP / maxTokens / 推理强度。 */
    public static GenerateOptions buildOptions(CustomerWorkProperties.Model cfg) {
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

    /**
     * 解析百炼 API Key：优先用显式配置值，其次回落环境变量 {@value #ENV_DASHSCOPE_API_KEY}；两者皆空则抛异常。
     *
     * @param configured 配置项传入的 Key（可空）
     * @return 去空白后的可用 Key
     * @throws IllegalStateException 配置与环境变量都缺失时
     */
    public static String resolveDashScopeKey(String configured) {
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        String fromEnv = System.getenv(ENV_DASHSCOPE_API_KEY);
        if (StringUtils.hasText(fromEnv)) {
            return fromEnv.trim();
        }
        throw new IllegalStateException(
            "未找到百炼 API Key。请配置 customer-work.model.api-key，"
          + "或设置环境变量 " + ENV_DASHSCOPE_API_KEY + "=你的密钥");
    }
}
