package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;

import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import io.agentscope.core.model.ModelContextWindows;
import java.util.Arrays;
import java.util.Map;

/**
 * 支持的模型厂商枚举。集中承载厂商编码，并做未知厂商的 fast fail 收口（{@link #of(String)}），
 * 避免 provider 字符串在 {@link AdminModelFactory} 里散落硬编码判断。
 *
 * <p>baseUrl 必填由 {@code ModelSaveRequest} 的 {@code @NotBlank} 收口（防御一处），
 * 各厂商默认 Base URL 的预填由前端 {@code ModelManage.vue} 的 providerPresets 负责，后端不重复兜底。</p>
 *
 * <p><b>成员必须与 {@link ModelProviders#SUPPORTED} 完全一致</b>，由
 * {@code ModelProviderCoverageTest} 下断言。此前这里只有四家原生厂商，
 * 而 starter 的建模工厂已支持九家——批次五新增的 GLM/DeepSeek/Kimi/MiniMax
 * 在后台根本登记不进去，Ollama 本地部署更是整个进不了 ModelOps。
 * 「支持哪些厂商」有两处真相，就一定会出现这种一边能用一边不能用的状态。</p>
 * @author owlzhangfq@gmail.com
 */
public enum ModelProvider {

    /** OpenAI 及所有 OpenAI 兼容端点（默认厂商）。 */
    OPENAI(ModelProviders.OPENAI, ModelContextWindows.OPENAI),
    /** 阿里云百炼 DashScope 原生协议（通义千问）。 */
    DASHSCOPE(ModelProviders.DASHSCOPE, ModelContextWindows.DASHSCOPE),
    /** Anthropic Claude 原生协议。 */
    ANTHROPIC(ModelProviders.ANTHROPIC, ModelContextWindows.ANTHROPIC),
    /** Google Gemini（Gemini Developer API，非 Vertex）。 */
    GEMINI(ModelProviders.GEMINI, ModelContextWindows.GEMINI),
    /**
     * Ollama 本地私有化部署：跑在自己机房里，<b>不需要 API Key</b>。
     *
     * <p>没有窗口推断表：本地部署的模型名由部署者自己起，任何硬编码清单都猜不中，
     * 窗口只能靠运营在资产里登记。</p>
     */
    OLLAMA(ModelProviders.OLLAMA, null),
    /** 智谱 GLM（OpenAI 兼容协议 + 专用 Formatter）。 */
    GLM(ModelProviders.GLM, ModelContextWindows.GLM),
    /** DeepSeek（OpenAI 兼容协议 + 专用 Formatter）。 */
    DEEPSEEK(ModelProviders.DEEPSEEK, ModelContextWindows.DEEPSEEK),
    /** 月之暗面 Kimi（OpenAI 兼容协议 + 专用 Formatter）。 */
    KIMI(ModelProviders.KIMI, ModelContextWindows.KIMI),
    /** MiniMax（OpenAI 兼容协议 + 专用 Formatter）。 */
    MINIMAX(ModelProviders.MINIMAX, ModelContextWindows.MINIMAX);

    private final String code;

    /**
     * 该厂商在框架里的上下文窗口推断表；{@code null} 表示框架没有收录这家。
     *
     * <p>框架的 {@code Model#getContextWindowSize()} 只会查<b>接入协议对应</b>的那张表：
     * 把智谱模型按 OpenAI 兼容端点登记时，框架查的是 {@code OPENAI} 表，
     * 于是 {@code glm-5.2} 这种名字一律推断为 0（含义是"表里没有"而不是"窗口为零"）。
     * 而 AgentScope 2.0.0 之后陆续补上了 GLM / DeepSeek / Kimi / MiniMax 四张表，
     * 只要运营按真实厂商登记 provider，这里就能给出比 0 更有用的回落值。</p>
     */
    private final Map<String, Integer> contextWindowTable;

    ModelProvider(String code, Map<String, Integer> contextWindowTable) {
        this.code = code;
        this.contextWindowTable = contextWindowTable;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按模型名推断上下文窗口；推断不出（厂商无表、或表里没有这个名字）返回 {@code null}。
     *
     * <p><b>只作为兜底</b>：运营在资产里登记的窗口永远优先，那是权威值。
     * 这里回答的是"运营没登记时，能不能给出一个比 0 更好的值"。返回 {@code null}
     * 而不是 0，是为了让调用方区分"推断不出来"与"窗口真的是 0"——
     * 这两者混同正是上线认证曾经对所有 OpenAI 兼容第三方部署恒判失败的病根。</p>
     */
    public Integer inferContextWindow(String modelName) {
        if (contextWindowTable == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        int inferred = ModelContextWindows.lookup(modelName, contextWindowTable);
        return inferred > 0 ? inferred : null;
    }

    /**
     * 按 provider 编码解析枚举；未知（含空）编码一律 fast fail，作为唯一的 provider 合法性收口点。
     */
    public static ModelProvider of(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        return Arrays.stream(values())
            .filter(p -> p.code.equals(normalized))
            .findFirst()
            .orElseThrow(() -> new BizException(ResultCode.MODEL_PROVIDER_NOT_SUPPORTED,
                "暂不支持的模型 provider: " + code));
    }
}
