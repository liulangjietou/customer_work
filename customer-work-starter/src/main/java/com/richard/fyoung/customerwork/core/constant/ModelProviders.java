package com.richard.fyoung.customerwork.core.constant;

/**
 * 大模型厂商标识常量。
 *
 * <p>取值即后台 {@code ai_model_config.provider} 列存的编码，客服端与后台必须认同一套字符串——
 * 此前 {@code ChatModelFactory}（建模型）与 {@code ChatModelProber}（探活）各写一份，
 * 后者注释里写着"与 admin 的 ModelProvider 编码一致"却无任何机制保证：三方任意一处新增厂商而另两处不跟，
 * 表现是"后台能配、探活通过、真跑起来落到兜底厂商"，链路上不报错。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ModelProviders {

    /** 阿里云百炼（provider 为空时的兜底厂商）。 */
    public static final String DASHSCOPE = "dashscope";

    /** OpenAI 及其兼容协议端点。 */
    public static final String OPENAI = "openai";

    /** Anthropic 原生 Messages 协议。 */
    public static final String ANTHROPIC = "anthropic";

    /** Google Gemini。 */
    public static final String GEMINI = "gemini";

    /** Ollama 本地私有化部署（无需 API Key）。 */
    public static final String OLLAMA = "ollama";

    /**
     * 智谱 GLM。
     *
     * <p><b>与"用 openai 走兼容协议"的区别</b>：框架为这几家提供了专用 Formatter
     * （{@code io.agentscope.extensions.model.openai.compat.*}）。走通用 OpenAI 兼容路径时
     * 消息格式是"尽力而为"的——上游修过一个 {@code DeepSeek formatter: preserve system role}
     * 的缺陷（#2189），也就是说通用路径会<b>丢掉 system role</b>。系统提示词是客服智能体
     * 全部行为约束的载体，丢了意味着人设、边界、话术规范在那一次调用里静默失效。</p>
     */
    public static final String GLM = "glm";

    /** DeepSeek。 */
    public static final String DEEPSEEK = "deepseek";

    /** 月之暗面 Kimi。 */
    public static final String KIMI = "kimi";

    /** MiniMax。 */
    public static final String MINIMAX = "minimax";

    /**
     * {@link com.richard.fyoung.customerwork.infra.config.ChatModelFactory} 实际支持的全部厂商。
     *
     * <p><b>为什么要有这份清单</b>：「支持哪些厂商」此前有两处真相——starter 的建模工厂
     * switch 与 admin 的 {@code ModelProvider} 枚举。两边不同步的后果是
     * <b>starter 能建、后台却登记不进去</b>：批次五给 starter 加了 GLM/DeepSeek/Kimi/MiniMax
     * 四家专用 Formatter，而 admin 枚举至今只有四家原生厂商，那四个模型在后台完全用不了；
     * Ollama 同理，本地私有化部署整个进不了 ModelOps。</p>
     *
     * <p>现在以这份清单为唯一真相，两侧都引用它，并由门禁测试断言三者一致
     * （常量清单 / 工厂 switch / admin 枚举）。新增厂商只需改这里再补工厂分支。</p>
     */
    public static final java.util.Set<String> SUPPORTED = java.util.Set.of(
        DASHSCOPE, OPENAI, ANTHROPIC, GEMINI, OLLAMA, GLM, DEEPSEEK, KIMI, MINIMAX);

    /**
     * 本地/私有化部署的厂商：跑在自己机房里，<b>没有 API Key</b>。
     *
     * <p>后台新建模型此前无条件要求 apiKey，于是即便把 ollama 加进支持清单也仍然登记不了——
     * 「本地部署纳入模型治理」这件事卡在这一行校验上。</p>
     */
    public static final java.util.Set<String> LOCAL_DEPLOYMENT = java.util.Set.of(OLLAMA);

    /** 该厂商是否需要 API Key（本地私有化部署不需要）。 */
    public static boolean requiresApiKey(String provider) {
        return !LOCAL_DEPLOYMENT.contains(normalize(provider));
    }

    /** 归一化 provider 编码：null 与空白按默认厂商处理，其余转小写去空格。 */
    public static String normalize(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return normalized.isEmpty() ? DASHSCOPE : normalized;
    }

    /**
     * 各厂商的默认端点。
     *
     * <p>取自框架各 {@code ModelProvider} 内置的默认值——写在这里是为了让"没配 base-url 时
     * 请求发去哪"这件事在本项目里可见。配置了 {@code model.base-url} 时以配置为准
     * （自建网关、代理、私有化部署都靠它）。</p>
     */
    public static final class DefaultBaseUrls {

        public static final String GLM = "https://open.bigmodel.cn/api/paas/v4";
        public static final String DEEPSEEK = "https://api.deepseek.com";
        public static final String KIMI = "https://api.moonshot.cn/v1";
        public static final String MINIMAX = "https://api.minimaxi.com/v1";

        private DefaultBaseUrls() {
        }
    }

    private ModelProviders() {
    }
}
