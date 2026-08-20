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

    private ModelProviders() {
    }
}
