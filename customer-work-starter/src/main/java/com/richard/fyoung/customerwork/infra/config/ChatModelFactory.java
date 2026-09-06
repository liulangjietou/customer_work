package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.util.StringUtils;
import com.richard.fyoung.customerwork.infra.config.properties.ModelProperties;

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

    private ChatModelFactory() {
    }

    /**
     * 按厂商构建模型，上下文窗口交给框架按模型名前缀推断。
     *
     * <p>框架的推断表只收录各厂商官方模型名（{@code gpt-4o} / {@code qwen-plus} 等），
     * 第三方模型走 OpenAI 兼容协议接入时推断不出来、{@code getContextWindowSize()} 返回 0。
     * 有权威声明值的场景请改用 {@link #build(String, String, String, String, boolean,
     * GenerateOptions, Boolean, Boolean, Integer)}。</p>
     */
    public static Model build(String provider, String modelName, String apiKey, String baseUrl,
                              boolean stream, GenerateOptions options,
                              Boolean enableSearch, Boolean enableThinking) {
        return build(provider, modelName, apiKey, baseUrl, stream, options,
            enableSearch, enableThinking, null);
    }

    /**
     * 按厂商构建模型。stream 开关与高级生成参数对各厂商统一应用；{@code baseUrl} 有值才覆盖（否则用厂商默认端点）。
     *
     * <p>{@code contextWindowSize} 传入时覆盖框架的模型名推断。框架推断表只认各厂商官方模型名，
     * 第三方模型（glm / deepseek / 自建网关）走 OpenAI 兼容协议时一律推断为 0，而 0 会被下游
     * 当成「窗口为零」而非「未知」——路由按各档取 min 上报能力、上线认证按窗口判定门槛，都会因此失真。
     * 调用方拿得到权威声明（如模型资产登记的窗口）时必须传进来。</p>
     *
     * @param provider          厂商标识（大小写不敏感，空则 dashscope）
     * @param modelName         模型名
     * @param apiKey            鉴权密钥（ollama 本地私有化不需要）
     * @param baseUrl           自定义端点（可空）
     * @param stream            是否流式
     * @param options           高级生成参数（温度 / topP / maxTokens / 推理强度）
     * @param enableSearch      DashScope 联网搜索（仅 dashscope 生效，可空）
     * @param enableThinking    DashScope 深度思考（仅 dashscope 生效，可空）
     * @param contextWindowSize 权威上下文窗口（可空；空或非正数则回落框架推断）
     */
    public static Model build(String provider, String modelName, String apiKey, String baseUrl,
                              boolean stream, GenerateOptions options,
                              Boolean enableSearch, Boolean enableThinking,
                              Integer contextWindowSize) {
        String p = provider == null ? ModelProviders.DASHSCOPE : provider.toLowerCase();
        boolean declaredWindow = contextWindowSize != null && contextWindowSize > 0;
        switch (p) {
            case ModelProviders.GLM:
                return openAiCompatible(modelName, apiKey, baseUrl, stream, options,
                    contextWindowSize, new GLMFormatter(), ModelProviders.DefaultBaseUrls.GLM);
            case ModelProviders.DEEPSEEK:
                return openAiCompatible(modelName, apiKey, baseUrl, stream, options,
                    contextWindowSize, new DeepSeekFormatter(), ModelProviders.DefaultBaseUrls.DEEPSEEK);
            case ModelProviders.KIMI:
                return openAiCompatible(modelName, apiKey, baseUrl, stream, options,
                    contextWindowSize, new KimiFormatter(), ModelProviders.DefaultBaseUrls.KIMI);
            case ModelProviders.MINIMAX:
                return openAiCompatible(modelName, apiKey, baseUrl, stream, options,
                    contextWindowSize, new MiniMaxFormatter(), ModelProviders.DefaultBaseUrls.MINIMAX);
            case ModelProviders.OPENAI: {
                OpenAIChatModel.Builder b = OpenAIChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(stream).generateOptions(options);
                if (declaredWindow) {
                    b.contextWindowSize(contextWindowSize);
                }
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case ModelProviders.ANTHROPIC: {
                AnthropicChatModel.Builder b = AnthropicChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(stream).defaultOptions(options);
                if (declaredWindow) {
                    b.contextWindowSize(contextWindowSize);
                }
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case ModelProviders.GEMINI: {
                // 注意：Gemini 需额外引入 google-genai 依赖
                GeminiChatModel.Builder b = GeminiChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).streamEnabled(stream).defaultOptions(options);
                if (declaredWindow) {
                    b.contextWindowSize(contextWindowSize);
                }
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            case ModelProviders.OLLAMA: {
                // Ollama 本地私有化：使用 OllamaOptions（生成参数另行配置），默认 localhost:11434
                OllamaChatModel.Builder b = OllamaChatModel.builder().modelName(modelName);
                if (declaredWindow) {
                    b.contextWindowSize(contextWindowSize);
                }
                if (StringUtils.hasText(baseUrl)) {
                    b.baseUrl(baseUrl);
                }
                return b.build();
            }
            default: {
                DashScopeChatModel.Builder b = DashScopeChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(stream).defaultOptions(options);
                if (declaredWindow) {
                    b.contextWindowSize(contextWindowSize);
                }
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
    public static GenerateOptions buildOptions(ModelProperties cfg) {
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

    /**
     * 走 OpenAI 兼容协议、但使用厂商专用 Formatter 的建模路径。
     *
     * <p><b>为什么不直接用 provider=openai 配一个 base-url 了事</b>：通用 OpenAI 兼容路径的
     * 消息格式是"尽力而为"的。上游为此修过一个 {@code DeepSeek formatter: preserve system role}
     * 的缺陷（#2189）——也就是说通用路径会<b>丢掉 system role</b>。
     * 系统提示词是客服智能体全部行为约束的载体，丢了意味着人设、边界、话术规范
     * 在那一次调用里静默失效，而且不报错。</p>
     *
     * <p><b>顺带解决上下文窗口推断为 0 的问题</b>：框架按模型名前缀查表推断窗口，
     * 表里只有各厂商官方模型名。此前这几家走 openai 兼容接入时一律推断为 0，
     * 而 0 的含义是"表里没有这个名字"而不是"窗口为零"——上线认证的窗口检查因此恒为失败。
     * 显式声明的窗口在这里照常生效（{@code declaredWindow}），未声明时由专用 provider 自行处理。</p>
     */
    private static Model openAiCompatible(String modelName, String apiKey, String baseUrl,
                                          boolean stream, GenerateOptions options,
                                          Integer contextWindowSize,
                                          Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter,
                                          String defaultBaseUrl) {
        OpenAIChatModel.Builder b = OpenAIChatModel.builder()
            .apiKey(apiKey).modelName(modelName).stream(stream)
            .generateOptions(options)
            .formatter(formatter);
        if (contextWindowSize != null && contextWindowSize > 0) {
            b.contextWindowSize(contextWindowSize);
        }
        // 配置优先：自建网关、代理、私有化部署都靠 base-url 覆盖官方端点
        b.baseUrl(StringUtils.hasText(baseUrl) ? baseUrl : defaultBaseUrl);
        return b.build();
    }
}
