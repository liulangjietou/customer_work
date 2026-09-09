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
